"""Validate supplied lock records and store them in explicit JSON files."""

import json
import os
from pathlib import Path
from tempfile import NamedTemporaryFile

from .generated_lock import Lock
from .generated_lock_error import LockError


class LockException(Exception):
    """Lock failure with a generated, serializable error record."""

    def __init__(self, error: LockError):
        self.error = error
        super().__init__(error.message)


def _failure(code, operation, message, path=None):
    return LockException(LockError(code=code, operation=operation, message=message, path=path))


def _validate(data, operation, path):
    version = data.get("format_version") if isinstance(data, dict) else None
    if type(version) is int and version not in (1, 2):
        raise _failure("UNSUPPORTED_VERSION", operation,
                       f"Lock revision {version} is unsupported. Supply revision 1 or 2.", path)
    try:
        lock = Lock.model_validate(data)
        for item in lock.files:
            if version == 1 and "sha256" in item.model_fields_set:
                raise ValueError("revision 1 files must omit sha256")
            if version == 2 and item.sha256 is None:
                raise ValueError("revision 2 files must include sha256")
        selections = [(item.base_url, item.url) for item in lock.selections]
        file_pairs = [(item.base_url, item.url) for item in lock.files]
        if len(set(selections)) != len(selections):
            raise ValueError("each selection pair must occur once")
        if len(set(file_pairs)) != len(file_pairs):
            raise ValueError("each file pair must occur once, regardless of format")
        if not set(selections).issubset(file_pairs):
            raise ValueError("each selection must have a file with the same base_url and url")
    except ValueError as error:
        raise _failure("INVALID_LOCK", operation,
                       f"Invalid lock: {error}. Correct the supplied record.", path) from error
    return lock


def _decode(text, operation, path):
    try:
        data = json.loads(text)
    except ValueError as error:
        raise _failure("INVALID_LOCK", operation,
                       f"Invalid lock JSON: {error}. Supply UTF-8 JSON without a BOM.", path) from error
    return _validate(data, operation, path)


def _encode(lock, operation, path):
    try:
        data = lock.model_dump(mode="json", warnings=False)
    except ValueError as error:
        raise _failure("INVALID_LOCK", operation,
                       f"Invalid lock: {error}. Correct the supplied record.", path) from error
    _validate(data, operation, path)
    return json.dumps(data)


def decode_lock(text: str) -> Lock:
    """Validate and decode JSON without inspecting or retrieving its URLs.

    Raise LockException for invalid records or unsupported revisions.
    """
    return _decode(text, "DECODE", None)


def encode_lock(lock: Lock) -> str:
    """Validate and encode a complete supplied lock as JSON text.

    Raise LockException for invalid records or unsupported revisions.
    """
    return _encode(lock, "ENCODE", None)


def read_lock(path: str | os.PathLike[str]) -> Lock:
    """Read and validate UTF-8 JSON without a BOM.

    Raise LockException for read failures, invalid records or unsupported revisions.
    """
    supplied_path = os.fspath(path)
    try:
        text = Path(supplied_path).read_bytes().decode("utf-8")
    except UnicodeDecodeError as error:
        raise _failure("INVALID_LOCK", "READ",
                       "Invalid lock encoding. Supply UTF-8 JSON without a BOM.", supplied_path) from error
    except (OSError, ValueError) as error:
        raise _failure("STORAGE_ERROR", "READ",
                       f"Cannot read lock: {error}. Check the path and permissions.", supplied_path) from error
    return _decode(text, "READ", supplied_path)


def write_lock(path: str | os.PathLike[str], lock: Lock) -> None:
    """Validate before staging and atomically replace the destination entry.

    The parent must exist. A destination symlink is replaced. On failure, preserve
    the previous destination and raise LockException, reporting any temporary file
    that could not be removed.
    """
    supplied_path = os.fspath(path)
    content = _encode(lock, "WRITE", supplied_path).encode("utf-8")
    destination = Path(supplied_path)
    staged = None
    try:
        staged = NamedTemporaryFile(mode="wb", dir=destination.parent,
                                    prefix=f".{destination.name}.", suffix=".tmp", delete=False)
        try:
            staged.write(content)
        except OSError:
            # A second close failure must not replace the original write failure.
            try:
                staged.close()
            except OSError:
                pass
            raise
        staged.close()
        os.replace(staged.name, supplied_path)
    except (OSError, ValueError) as error:
        cleanup = ""
        if staged is not None:
            try:
                os.unlink(staged.name)
            except OSError:
                cleanup = f" Remove temporary file {staged.name} if it remains."
        raise _failure("STORAGE_ERROR", "WRITE",
                       f"Cannot replace lock: {error}. Check the path, permissions and atomic replacement support."
                       + cleanup, supplied_path) from error
