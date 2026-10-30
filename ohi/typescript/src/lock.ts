import type { ArtifactSelection, Lock } from "./generated_lock.js";
import type { LockError, LockOperation } from "./generated_lock_error.js";

export class LockException extends Error {
  constructor(readonly error: LockError, options?: ErrorOptions) {
    super(error.message, options);
    this.name = "LockException";
  }
}

function pair({ base_url, url }: ArtifactSelection): string {
  return JSON.stringify([base_url, url]);
}

function uniquePairs(records: ArtifactSelection[], field: string): Set<string> {
  const pairs = new Set(records.map(pair));
  if (pairs.size !== records.length) throw new Error(`${field} contains a repeated (base_url, url) pair.`);
  return pairs;
}

function checkedLock(data: unknown, operation: LockOperation): Lock {
  if (typeof data === "object" && data !== null && !Array.isArray(data) &&
      "format_version" in data && Number.isInteger(data.format_version) &&
      data.format_version !== 1 && data.format_version !== 2) {
    throw new LockException({ code: "UNSUPPORTED_VERSION", operation,
      message: `Unsupported lock revision ${data.format_version}. Supply revision 1 or 2 or use a reader supporting this revision.` });
  }
  try {
    const lock = data as Lock;
    if (lock.format_version === 1 && lock.files.some((file) => file.sha256 !== undefined)) {
      throw new Error("Revision 1 files must omit sha256.");
    }
    if (lock.format_version === 2 && lock.files.some((file) => file.sha256 === undefined)) {
      throw new Error("Revision 2 files require sha256.");
    }
    const selections = uniquePairs(lock.selections, "selections");
    const files = uniquePairs(lock.files, "files");
    if ([...selections].some((selection) => !files.has(selection))) {
      throw new Error("Every selection must have a corresponding file record.");
    }
    return lock;
  } catch (cause) {
    throw new LockException({ code: "INVALID_LOCK", operation,
      message: `Invalid lock. Correct the supplied record. ${cause instanceof Error ? cause.message : String(cause)}` }, { cause });
  }
}

/** Validate and decode a supplied lock without inspecting publications or retrieving files.
 * Throws LockException for invalid records or unsupported revisions.
 */
export function decodeLock(text: string): Lock {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch (cause) {
    throw new LockException({ code: "INVALID_LOCK", operation: "DECODE",
      message: "Invalid lock JSON. Supply JSON text without a leading BOM." }, { cause });
  }
  return checkedLock(data, "DECODE");
}

/** Validate and encode the complete supplied lock, preserving strings and array order.
 * Throws LockException for invalid records or unsupported revisions.
 */
export function encodeLock(lock: Lock): string {
  return JSON.stringify(checkedLock(lock, "ENCODE"));
}
