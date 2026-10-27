---
title: Search, inspect and retrieve
description: Find publications in a catalog, read their metadata and download their files.
---

Finding and downloading a file takes three calls: `search` finds publications, `inspect` reads a
publication's metadata, and `get` downloads one of its files.

```python
from ohi import CatalogClient, SearchRequest

client = CatalogClient()
page = client.search(SearchRequest(q="sensors", page=1, per_page=10))
if page.hits:
    publication = client.inspect(page.hits[0].base_url)
    artifact = publication.artifacts[0]
    content = client.get(artifact.url)
```

In TypeScript, the same calls return promises:

```typescript
import { CatalogClient } from "@neverblink/ohi";

const page = await new CatalogClient().search({ q: "sensors" });
```

## Search

Search uses ohi.dev by default. Each call returns one page. Use `page` to request the next page,
`per_page` to request up to 250 results, and `q="*"` to browse. See the
[search options](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/ohi/model/schema/search-request.yaml)
for filtering, sorting and facets.

When a search reports `search_cutoff: true`, its results or counts may be incomplete.

## Inspect and get

Pass a hit's `base_url` to `inspect`, choose an artifact in the format you need, then pass its
`url` to `get`. The example above retrieves the first artifact. `get` returns the file's bytes for
your application to save or load. Dependency files must be selected and retrieved separately.

A known publication or artifact URL can be passed directly to `inspect` or `get`, without
searching first.

## Using another catalog

To use another catalog, supply both `search_url` and `query_by`:

```python
from ohi import CatalogClient, CatalogConfig

client = CatalogClient(CatalogConfig(
    search_url="https://catalog.example/search/collections/packages/documents/search",
    query_by="title,name",
))
```

In TypeScript, pass the fields directly: `new CatalogClient({ search_url, query_by })`.

Replace the example endpoint and indexed fields with your catalog's values. Omitting both selects
ohi.dev. Supplying only one is an error.
