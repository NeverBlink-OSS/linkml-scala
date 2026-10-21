import { randomUUID } from "node:crypto";
import fs, { type FileHandle } from "node:fs/promises";
import { basename, dirname, join } from "node:path";
import type { Lock } from "./generated_lock.js";
import { decodeLock, encodeLock, LockException } from "./lock.js";

function fileFailure(cause: unknown, operation: "READ" | "WRITE", path: string, message: string): LockException {
  if (cause instanceof LockException) {
    return new LockException({ ...cause.error, operation, path }, { cause: cause.cause ?? cause });
  }
  return new LockException({ code: "STORAGE_ERROR", operation, path, message }, { cause });
}

/** Read and validate UTF-8 JSON without a BOM.
 * Rejects with LockException for read failures, invalid records or unsupported revisions.
 */
export async function readLock(path: string): Promise<Lock> {
  let bytes: Uint8Array;
  try {
    bytes = await fs.readFile(path);
  } catch (cause) {
    throw fileFailure(cause, "READ", path, "Cannot read the lock. Check the path and read permissions.");
  }
  let text: string;
  try {
    // Retain the BOM so the JSON parser rejects it instead of silently removing it.
    text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch (cause) {
    throw new LockException({ code: "INVALID_LOCK", operation: "READ", path,
      message: "Invalid lock encoding. Save the file as UTF-8 without a BOM." }, { cause });
  }
  try {
    return decodeLock(text);
  } catch (cause) {
    throw fileFailure(cause, "READ", path, "Cannot decode the lock. Correct the supplied file.");
  }
}

/** Validate before staging and atomically replace the destination entry, including a symlink.
 * The parent must exist. On failure, preserves the previous destination and rejects with
 * LockException, reporting any temporary file that could not be removed.
 */
export async function writeLock(path: string, lock: Lock): Promise<void> {
  let text: string;
  try {
    text = encodeLock(lock);
  } catch (cause) {
    throw fileFailure(cause, "WRITE", path, "Cannot encode the lock. Correct the supplied record.");
  }

  let temporary: string | undefined;
  let handle: FileHandle | undefined;
  try {
    const candidate = join(dirname(path), `.${basename(path)}.${randomUUID()}.tmp`);
    handle = await fs.open(candidate, "wx");
    temporary = candidate;
    await handle.writeFile(text, "utf8");
    await handle.close();
    handle = undefined;
    await fs.rename(temporary, path);
    temporary = undefined;
  } catch (cause) {
    if (handle) await handle.close().catch(() => {});
    let message = "Cannot replace the lock. Check the parent directory, permissions and atomic rename support.";
    if (temporary) {
      try {
        await fs.unlink(temporary);
      } catch {
        message += ` Remove the retained temporary file ${temporary}.`;
      }
    }
    throw fileFailure(cause, "WRITE", path, message);
  }
}
