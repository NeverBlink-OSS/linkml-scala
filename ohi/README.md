# Ohi SDK

The Ohi SDK lets Scala/JVM, Python and TypeScript applications search publications, inspect metadata, download selected files and save supplied file selections.

## Search, inspect and retrieve

Install the Python SDK on Python 3.10 or later:

```sh
python -m pip install ohi-sdk
```

```python
from ohi import CatalogClient, SearchRequest

client = CatalogClient()
page = client.search(SearchRequest(q="sensors", page=1, per_page=10))
if page.hits:
    publication = client.inspect(page.hits[0].base_url)
    artifact = publication.artifacts[0]
    content = client.get(artifact.url)
```

Search uses ohi.dev by default. Each call returns one page. Use `page` to request the next page,
`per_page` to request up to 250 results, and `q="*"` to browse. See the
[search options](model/schema/search-request.yaml) for filtering, sorting and facets.

Pass a hit's `base_url` to `inspect`, choose an artifact in the format you need, then pass its
`url` to `get`. The example retrieves the first artifact. `get` returns the file's bytes for your
application to save or load. Dependency files must be selected and retrieved separately.

To use another catalog, supply both `search_url` and `query_by`:

```python
from ohi import CatalogClient, CatalogConfig

client = CatalogClient(CatalogConfig(
    search_url="https://catalog.example/search/collections/packages/documents/search",
    query_by="title,name",
))
```

Replace the example endpoint and indexed fields with your catalog's values. Omitting both
selects ohi.dev. Supplying only one is an error. A known publication or artifact URL can be
passed directly to `inspect` or `get`, without searching first.

## Language bindings

| Language | Client | Operation results |
| --- | --- | --- |
| Scala/JVM | `eu.neverblink.ohi.catalog.CatalogClient` in `ohi.catalog.jvm` | Synchronous `SearchResult`, `Publication`, `Array[Byte]` |
| Python | `ohi.CatalogClient` | Synchronous `SearchResult`, `Publication`, `bytes` |
| TypeScript | `CatalogClient` from the package entry point | Promises of `SearchResult`, `Publication`, `Uint8Array` |

TypeScript supports Node 22+ and browsers with Fetch. Browsers need CORS access to the catalog,
publication and artifact endpoints.

```sh
npm install @neverblink/ohi
```

```typescript
import { CatalogClient } from "@neverblink/ohi";

const page = await new CatalogClient().search({ q: "sensors" });
```

For another catalog, pass the fields directly: `new CatalogClient({ search_url, query_by })`.

Scala modules are built from source and included in the CLI. They are not published separately to
Maven Central. The CLI, Python package and TypeScript package share the linkml-scala release version.

## Save and reload file selections

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

The URLs are illustrative. When dependencies exist, include their selected file records in
`files` as well. The [two-file example](examples/selection.lock.json) shows this distinction.
The [lock schema](model/schema/lock.yaml) defines the record. A lock stores file choices. It does
not confirm that files were downloaded or that dependency selections are complete.

| Binding | JSON text | Explicit file path |
| --- | --- | --- |
| Scala/JVM, `eu.neverblink.ohi.model` | `LockJson.decode`, `LockJson.encode` | `LockFiles.read`, `LockFiles.write`, using `java.nio.file.Path` |
| Python, `ohi` | `decode_lock`, `encode_lock` | `read_lock`, `write_lock`, using a string or path-like object |
| TypeScript, `@neverblink/ohi` | `decodeLock`, `encodeLock` | `readLock`, `writeLock` from `@neverblink/ohi/node`, returning promises |

Create the parent directory before writing a lock. Writes replace an existing lock and preserve
its contents on failure. Browser applications use `encodeLock` and `decodeLock` with their own
storage. File operations are available through the TypeScript `/node` entry.

## Metadata

The SDK accepts [revision 2 publication metadata](model/schema/package.yaml), illustrated by
the synthetic [Sensors example](examples/sensors-package.json). Check the publication's licenses
and each artifact's format and loading roots. Verify downloaded bytes against its `size_bytes`
and `sha256` if needed. `get` does not perform that check.

## Failures and HTTP behavior

Catalog failures raise `CatalogException`. Lock validation and file failures raise `LockException`.
Both expose an `.error` record with a `code` and explanatory `message`.
See the [error schemas](model/schema/) for additional fields.

Use the final HTTP(S) URL for publications and files. Requests do not follow redirects or retry.
Set `CatalogConfig.timeout_ms` to override the default 30000 ms timeout.
When a search reports `search_cutoff: true`, its results or counts may be incomplete.

## Regenerate and test

For SDK development, run from the repository root:

```sh
./mill --no-server regenerateOhi
python -m pip install ./ohi/python
npm --prefix ohi/typescript ci
./mill --no-server 'ohi.{model,catalog}.jvm.test.testForked'
python -m unittest discover -s ohi/python/tests -v
npm --prefix ohi/typescript test
```

Run `npm --prefix ohi/typescript run test:browser` for browser checks using Chrome.
