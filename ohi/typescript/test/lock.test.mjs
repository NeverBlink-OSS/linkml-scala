import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { decodeLock, encodeLock, LockException } from "@neverblink/ohi";

function example() {
  return JSON.parse(readFileSync(new URL("../../examples/selection.lock.json", import.meta.url), "utf8"));
}

function assertValues(lock) {
  assert.deepEqual(Object.keys(lock).sort(), ["files", "format_version", "selections"]);
  assert.equal(lock.format_version, 1);
  assert.deepEqual(lock.selections, [{
    base_url: "https://Hub.example/releases/z/2/?v=%2F#chosen",
    url: "https://Files.example/Z%2F.ttl?download=1#file",
  }]);
  assert.deepEqual(lock.files, [
    { base_url: "https://Hub.example/releases/z/2/?v=%2F#chosen",
      url: "https://Files.example/Z%2F.ttl?download=1#file", format: "owl-turtle" },
    { base_url: "https://Hub.example/releases/a/1/", url: "https://files.example/a.yaml", format: "linkml-yaml" },
  ]);
}

function rejects(action, code = "INVALID_LOCK", operation = "DECODE") {
  let failure;
  assert.throws(action, (error) => {
    assert.ok(error instanceof LockException);
    assert.equal(error.error.code, code);
    assert.equal(error.error.operation, operation);
    assert.equal("path" in error.error, false);
    assert.ok(error.error.message.length > 0);
    assert.deepEqual(Object.keys(JSON.parse(JSON.stringify(error.error))).sort(), ["code", "message", "operation"]);
    failure = error;
    return true;
  });
  return failure;
}

test("decode preserves exact URLs, file formats and supplied order", () => {
  assertValues(decodeLock(JSON.stringify(example())));
});

test("encode emits the complete record with exact URLs, formats and order", () => {
  assertValues(JSON.parse(encodeLock(example())));
});

test("encode preserves direct selection order", () => {
  const lock = example();
  lock.selections.push({ base_url: "https://Hub.example/releases/a/1/", url: "https://files.example/a.yaml" });
  const encoded = JSON.parse(encodeLock(lock));
  assert.deepEqual(encoded.selections.map(({ base_url, url }) => [base_url, url]), [
    ["https://Hub.example/releases/z/2/?v=%2F#chosen", "https://Files.example/Z%2F.ttl?download=1#file"],
    ["https://Hub.example/releases/a/1/", "https://files.example/a.yaml"],
  ]);
});

test("integral revision 1.0 accepts required empty arrays", () => {
  const lock = decodeLock('{"format_version":1.0,"selections":[],"files":[]}');
  assert.deepEqual(lock, { format_version: 1, selections: [], files: [] });
  assert.deepEqual(JSON.parse(encodeLock(lock)), { format_version: 1, selections: [], files: [] });
});

test("malformed JSON retains the native parser cause", () => {
  assert.ok(rejects(() => decodeLock("{")).cause instanceof SyntaxError);
});

test("text decoding rejects a leading BOM", () => {
  rejects(() => decodeLock('\uFEFF{"format_version":1,"selections":[],"files":[]}'));
});

test("unsupported integral revision takes precedence over missing revision-specific fields", () => {
  rejects(() => decodeLock('{"format_version":2.0}'), "UNSUPPORTED_VERSION");
});

for (const [name, change] of [
  ["missing revision", (lock) => { delete lock.format_version; }],
  ["boolean revision", (lock) => { lock.format_version = true; }],
  ["fractional revision", (lock) => { lock.format_version = 1.5; }],
  ["string revision", (lock) => { lock.format_version = "1"; }],
  ["missing selections", (lock) => { delete lock.selections; }],
  ["missing files", (lock) => { delete lock.files; }],
  ["null selections", (lock) => { lock.selections = null; }],
  ["null files", (lock) => { lock.files = null; }],
  ["unknown root field", (lock) => { lock.extra = true; }],
  ["unknown selection field", (lock) => { lock.selections[0].extra = true; }],
  ["unknown file field", (lock) => { lock.files[0].extra = true; }],
  ["unknown format", (lock) => { lock.files[0].format = "future-format"; }],
  ["relative URI", (lock) => { lock.files[1].url = "relative.ttl"; }],
  ["duplicate selection", (lock) => { lock.selections.push({ ...lock.selections[0] }); }],
  ["identical duplicate file", (lock) => { lock.files.push({ ...lock.files[0] }); }],
  ["conflicting duplicate file", (lock) => { lock.files.push({ ...lock.files[0], format: "owl-rdfxml" }); }],
  ["selection without a file", (lock) => { lock.files.shift(); }],
]) {
  test(`decode rejects ${name}`, () => {
    const lock = example();
    change(lock);
    rejects(() => decodeLock(JSON.stringify(lock)));
  });
}

for (const [name, base, url, expected] of [
  ["same file URL in another publication", "https://Hub.example/releases/a/1/", "https://Files.example/Z%2F.ttl?download=1#file",
    ["https://Hub.example/releases/a/1/", "https://Files.example/Z%2F.ttl?download=1#file"]],
  ["another file in the same publication", "https://Hub.example/releases/z/2/?v=%2F#chosen", "https://files.example/other.ttl",
    ["https://Hub.example/releases/z/2/?v=%2F#chosen", "https://files.example/other.ttl"]],
  ["URI-equivalent spelling", "https://hub.example/releases/z/2/?v=%2F#chosen", "https://files.example/Z%2f.ttl?download=1#file",
    ["https://hub.example/releases/z/2/?v=%2F#chosen", "https://files.example/Z%2f.ttl?download=1#file"]],
]) {
  test(`exact pair validation preserves ${name}`, () => {
    const lock = example();
    lock.selections.push({ base_url: base, url });
    lock.files.push({ base_url: base, url, format: "owl-turtle" });
    const decoded = decodeLock(JSON.stringify(lock));
    assert.deepEqual(decoded.selections.map(({ base_url, url }) => [base_url, url]), [
      ["https://Hub.example/releases/z/2/?v=%2F#chosen", "https://Files.example/Z%2F.ttl?download=1#file"], expected,
    ]);
    assert.deepEqual([decoded.files[2].base_url, decoded.files[2].url], expected);
    assert.equal(decoded.files[2].format, "owl-turtle");
  });
}

test("encode rejects schema-invalid records without dropping fields", () => {
  rejects(() => encodeLock({ ...example(), extra: true }), "INVALID_LOCK", "ENCODE");
});

test("encode enforces selection-to-file relations", () => {
  const lock = example();
  lock.files = [];
  rejects(() => encodeLock(lock), "INVALID_LOCK", "ENCODE");
});

test("encode reports unsupported revision before validating lists", () => {
  rejects(() => encodeLock({ format_version: 2 }), "UNSUPPORTED_VERSION", "ENCODE");
});
