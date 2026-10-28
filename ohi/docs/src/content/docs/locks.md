---
title: Save and reload file selections
description: Save and reload file selections.
---

Supply all selected and dependency files with their hashes from publication metadata.

```python
from ohi import ArtifactSelection, Lock, LockedFile, read_lock, write_lock

selected = LockedFile(
    base_url="https://hub.example/releases/energy/2/",
    url="https://files.example/energy.ttl",
    format="owl-turtle",
    sha256="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
)
lock = Lock(
    format_version=2,
    selections=[ArtifactSelection(base_url=selected.base_url, url=selected.url)],
    files=[selected],
)
write_lock("ohi.lock.json", lock)
saved = read_lock("ohi.lock.json")
```

The URLs and hash are illustrative.
[Version-1 locks](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/ohi/examples/selection.lock.json)
omit hashes and remain supported. See the
[lock schema](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/ohi/model/schema/lock.yaml).

## In each language

| Binding | JSON text | Explicit file path |
| --- | --- | --- |
| Scala/JVM, `eu.neverblink.ohi.model` | `LockJson.decode`, `LockJson.encode` | `LockFiles.read`, `LockFiles.write`, using `java.nio.file.Path` |
| Python, `ohi` | `decode_lock`, `encode_lock` | `read_lock`, `write_lock`, using a string or path-like object |
| TypeScript, `@neverblink/ohi` | `decodeLock`, `encodeLock` | `readLock`, `writeLock` from `@neverblink/ohi/node`, returning promises |

Create the parent directory before writing a lock. Writes replace an existing lock and preserve its
contents on failure. Browser applications use `encodeLock` and `decodeLock` with their own storage.
File operations are available through the TypeScript `/node` entry.
