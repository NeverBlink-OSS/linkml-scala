---
title: Metadata, failures and HTTP
description: The publication metadata the SDK reads, the errors it raises and how it makes requests.
TODO: OHI-96 replace this with proper tutorials
---

## Metadata

The SDK accepts
[revision 2 publication metadata](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/ohi/model/schema/package.yaml),
illustrated by the synthetic
[Sensors example](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/ohi/examples/sensors-package.json).
Check the publication's licenses and each artifact's format and loading roots. Verify downloaded
bytes against its `size_bytes` and `sha256` if needed. `get` does not perform that check.

## Failures

Catalog failures raise `CatalogException`. Lock validation and file failures raise `LockException`.
Both expose an `.error` record with a `code` and explanatory `message`. See the
[error schemas](https://github.com/NeverBlink-OSS/linkml-scala/tree/main/ohi/model/schema) for
additional fields.

## HTTP behavior

Use the final HTTP(S) URL for publications and files. Requests do not follow redirects or retry.
Set `CatalogConfig.timeout_ms` to override the default 30000 ms timeout.
