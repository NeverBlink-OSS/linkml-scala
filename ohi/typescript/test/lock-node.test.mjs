import assert from "node:assert/strict";
import fs from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join } from "node:path";
import { test } from "node:test";
import { LockException } from "@neverblink/ohi";
import { readLock, writeLock } from "@neverblink/ohi/node";

const empty = () => ({ format_version: 1, selections: [], files: [] });
const original = Buffer.from("original invalid lock\n");

async function fixture(t) {
  const directory = await fs.mkdtemp(join(tmpdir(), "ohi-lock-"));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  return { directory, path: `${directory}/./ohi.lock.json` };
}

async function rejects(action, code, operation, path, cause) {
  let failure;
  await assert.rejects(action, (error) => {
    assert.ok(error instanceof LockException);
    assert.equal(error.error.code, code);
    assert.equal(error.error.operation, operation);
    assert.equal(error.error.path, path);
    assert.ok(error.error.message.length > 0);
    if (cause) assert.equal(error.cause, cause);
    failure = error;
    return true;
  });
  return failure;
}

async function preserved(directory, path) {
  assert.deepEqual(await fs.readFile(path), original);
  assert.deepEqual(await fs.readdir(directory), ["ohi.lock.json"]);
}

test("write creates a complete UTF-8 file through an exclusive sibling", async (t) => {
  const { directory, path } = await fixture(t);
  const open = t.mock.method(fs, "open");
  await writeLock(path, empty());
  const bytes = await fs.readFile(path);
  assert.deepEqual(JSON.parse(bytes.toString("utf8")), { format_version: 1, selections: [], files: [] });
  assert.notDeepEqual([...bytes.subarray(0, 3)], [0xef, 0xbb, 0xbf]);
  assert.equal(open.mock.calls.length, 1);
  assert.equal(dirname(open.mock.calls[0].arguments[0]), directory);
  assert.equal(open.mock.calls[0].arguments[1], "wx");
  assert.deepEqual(await fs.readdir(directory), ["ohi.lock.json"]);
});

test("write replaces an invalid old file as a whole", async (t) => {
  const { directory, path } = await fixture(t);
  await fs.writeFile(path, original);
  await writeLock(path, empty());
  assert.deepEqual(JSON.parse(await fs.readFile(path, "utf8")), { format_version: 1, selections: [], files: [] });
  assert.deepEqual(await fs.readdir(directory), ["ohi.lock.json"]);
});

test("read returns the supplied file records without changing bytes", async (t) => {
  const { directory, path } = await fixture(t);
  const bytes = await fs.readFile(new URL("../../examples/selection.lock.json", import.meta.url));
  await fs.writeFile(path, bytes);
  const lock = await readLock(path);
  assert.equal(lock.format_version, 1);
  assert.deepEqual(lock.selections, [{ base_url: "https://Hub.example/releases/z/2/?v=%2F#chosen",
    url: "https://Files.example/Z%2F.ttl?download=1#file" }]);
  assert.deepEqual(lock.files.map(({ url, format }) => [url, format]), [
    ["https://Files.example/Z%2F.ttl?download=1#file", "owl-turtle"],
    ["https://files.example/a.yaml", "linkml-yaml"],
  ]);
  assert.deepEqual(await fs.readFile(path), bytes);
  assert.deepEqual(await fs.readdir(directory), ["ohi.lock.json"]);
});

for (const [name, bytes] of [
  ["malformed UTF-8", Buffer.from([0x7b, 0xff, 0x7d])],
  ["UTF-8 BOM", Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(JSON.stringify(empty()))])],
  ["UTF-16LE", Buffer.from(JSON.stringify(empty()), "utf16le")],
  ["malformed JSON", Buffer.from("{")],
]) {
  test(`read rejects ${name} and preserves the input`, async (t) => {
    const { directory, path } = await fixture(t);
    await fs.writeFile(path, bytes);
    await rejects(() => readLock(path), "INVALID_LOCK", "READ", path);
    assert.deepEqual(await fs.readFile(path), bytes);
    assert.deepEqual(await fs.readdir(directory), ["ohi.lock.json"]);
  });
}

test("read retains unsupported-version classification and caller path", async (t) => {
  const { directory, path } = await fixture(t);
  await fs.writeFile(path, '{"format_version":2.0}');
  await rejects(() => readLock(path), "UNSUPPORTED_VERSION", "READ", path);
  assert.equal(await fs.readFile(path, "utf8"), '{"format_version":2.0}');
  assert.deepEqual(await fs.readdir(directory), ["ohi.lock.json"]);
});

test("read reports missing input without creating an empty lock", async (t) => {
  const { directory, path } = await fixture(t);
  const failure = await rejects(() => readLock(path), "STORAGE_ERROR", "READ", path);
  assert.equal(failure.cause.code, "ENOENT");
  assert.deepEqual(await fs.readdir(directory), []);
});

test("write refuses an absent parent without creating directories", async (t) => {
  const { directory } = await fixture(t);
  const path = `${directory}/missing/ohi.lock.json`;
  await rejects(() => writeLock(path, empty()), "STORAGE_ERROR", "WRITE", path);
  assert.deepEqual(await fs.readdir(directory), []);
});

for (const [name, lock, code] of [
  ["invalid input", { format_version: 1, selections: [], files: null }, "INVALID_LOCK"],
  ["unsupported revision", { format_version: 2 }, "UNSUPPORTED_VERSION"],
]) {
  test(`write rejects ${name} before opening any file`, async (t) => {
    const { directory, path } = await fixture(t);
    await fs.writeFile(path, original);
    const open = t.mock.method(fs, "open");
    await rejects(() => writeLock(path, lock), code, "WRITE", path);
    assert.equal(open.mock.calls.length, 0);
    await preserved(directory, path);
  });
}

for (const stage of ["write", "close"]) {
  test(`a staging ${stage} failure preserves old bytes and removes the temporary file`, async (t) => {
    const { directory, path } = await fixture(t);
    await fs.writeFile(path, original);
    const failure = new Error(`injected ${stage} failure`);
    const nativeOpen = fs.open;
    t.mock.method(fs, "open", async (...args) => {
      const handle = await nativeOpen(...args);
      if (stage === "write") {
        const nativeWrite = handle.writeFile.bind(handle);
        t.mock.method(handle, "writeFile", async () => {
          await nativeWrite("partial temporary bytes");
          throw failure;
        });
        // A subsequent close failure must not replace the original write failure.
        const nativeClose = handle.close.bind(handle);
        t.mock.method(handle, "close", async () => { await nativeClose(); throw new Error("secondary close failure"); });
      } else {
        const nativeClose = handle.close.bind(handle);
        t.mock.method(handle, "close", async () => { await nativeClose(); throw failure; });
      }
      return handle;
    });
    await rejects(() => writeLock(path, empty()), "STORAGE_ERROR", "WRITE", path, failure);
    await preserved(directory, path);
  });
}

test("unsupported atomic rename preserves old bytes without a fallback", async (t) => {
  const { directory, path } = await fixture(t);
  await fs.writeFile(path, original);
  const failure = Object.assign(new Error("atomic rename unsupported"), { code: "ENOTSUP" });
  const rename = t.mock.method(fs, "rename", async (temporary, destination) => {
    assert.equal(destination, path);
    assert.equal(dirname(temporary), directory);
    assert.deepEqual(JSON.parse(await fs.readFile(temporary, "utf8")), { format_version: 1, selections: [], files: [] });
    throw failure;
  });
  await rejects(() => writeLock(path, empty()), "STORAGE_ERROR", "WRITE", path, failure);
  assert.equal(rename.mock.calls.length, 1);
  await preserved(directory, path);
});

test("cleanup failure retains the original cause and identifies the temporary file", async (t) => {
  const { directory, path } = await fixture(t);
  await fs.writeFile(path, original);
  const primary = new Error("primary rename failure");
  let temporary;
  t.mock.method(fs, "rename", async (source) => { temporary = source; throw primary; });
  t.mock.method(fs, "unlink", async () => { throw new Error("secondary cleanup failure"); });
  const failure = await rejects(() => writeLock(path, empty()), "STORAGE_ERROR", "WRITE", path, primary);
  assert.ok(failure.message.includes(temporary));
  assert.deepEqual(await fs.readFile(path), original);
  assert.deepEqual((await fs.readdir(directory)).sort(), [basename(temporary), "ohi.lock.json"].sort());
  assert.deepEqual(JSON.parse(await fs.readFile(temporary, "utf8")), { format_version: 1, selections: [], files: [] });
});

test("successful replacement requires no subsequent temporary cleanup", async (t) => {
  const { directory, path } = await fixture(t);
  const unlink = t.mock.method(fs, "unlink", async () => { throw new Error("cleanup must not run after rename"); });
  await writeLock(path, empty());
  assert.equal(unlink.mock.calls.length, 0);
  assert.deepEqual(JSON.parse(await fs.readFile(path, "utf8")), { format_version: 1, selections: [], files: [] });
  assert.deepEqual(await fs.readdir(directory), ["ohi.lock.json"]);
});

test("writing a final symlink replaces the link and preserves its referent", async (t) => {
  const { directory, path } = await fixture(t);
  const referent = join(directory, "referent.json");
  await fs.writeFile(referent, original);
  try {
    await fs.symlink(referent, path);
  } catch (error) {
    if (error.code !== "EPERM" && error.code !== "ENOSYS") throw error;
    t.skip(`Host symlinks unavailable: ${error.code}`);
    return;
  }
  await writeLock(path, empty());
  assert.equal((await fs.lstat(path)).isSymbolicLink(), false);
  assert.deepEqual(await fs.readFile(referent), original);
  assert.deepEqual(JSON.parse(await fs.readFile(path, "utf8")), { format_version: 1, selections: [], files: [] });
  assert.deepEqual((await fs.readdir(directory)).sort(), ["ohi.lock.json", "referent.json"]);
});
