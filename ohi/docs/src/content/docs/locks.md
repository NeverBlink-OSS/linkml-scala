---
title: Save and reload file selections
description: Record the files an application picked in a lock file, and read it back later.
---

A lock records exact publication and artifact URLs plus each file's format, so an application can
reuse its selections without inspecting publication metadata again. The application supplies all
direct and dependency files. These operations perform no HTTP requests or dependency lookup.

For a publication with no dependencies, a complete record can contain one selected file:

```python
from ohi import ArtifactSelection, Lock, LockedFile, read_lock, write_lock

selected = LockedFile(
    base_url="https://hub.example/releases/energy/2/",
    url="https://files.example/energy.ttl",
    format="owl-turtle",
)
lock = Lock(
    format_version=1,
    selections=[ArtifactSelection(base_url=selected.base_url, url=selected.url)],
    files=[selected],
)
write_lock("ohi.lock.json", lock)
saved = read_lock("ohi.lock.json")
```

The URLs are illustrative. When dependencies exist, include their selected file records in `files`
as well. The
[two-file example](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/ohi/examples/selection.lock.json)
shows this distinction. The
[lock schema](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/ohi/model/schema/lock.yaml)
defines the record. A lock stores file choices. It does not confirm that files were downloaded or
that dependency selections are complete.

## In each language

| Binding | JSON text | Explicit file path |
| --- | --- | --- |
| Scala/JVM, `eu.neverblink.ohi.model` | `LockJson.decode`, `LockJson.encode` | `LockFiles.read`, `LockFiles.write`, using `java.nio.file.Path` |
| Python, `ohi` | `decode_lock`, `encode_lock` | `read_lock`, `write_lock`, using a string or path-like object |
| TypeScript, `@neverblink/ohi` | `decodeLock`, `encodeLock` | `readLock`, `writeLock` from `@neverblink/ohi/node`, returning promises |

Create the parent directory before writing a lock. Writes replace an existing lock and preserve its
contents on failure. Browser applications use `encodeLock` and `decodeLock` with their own storage.
File operations are available through the TypeScript `/node` entry.
