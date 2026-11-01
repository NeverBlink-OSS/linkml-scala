# ohi SDK for Python

Search ontologies on [ohi.dev](https://ohi.dev), inspect their metadata, download selected files and save file selections.

Install on Python 3.10 or later:

```sh
python -m pip install ohi-sdk
```

Search, inspect the first hit and download its first file:

```python
from ohi import CatalogClient, SearchRequest

client = CatalogClient()
page = client.search(SearchRequest(q="sensors", page=1, per_page=10))
if page.hits:
    publication = client.inspect(page.hits[0].base_url)
    artifact = publication.artifacts[0]
    content = client.get(artifact.url)
```

`get` returns the file's bytes.

**See the [SDK documentation](https://ohi.dev/docs/reference/sdks/)** for configuration, file selections, metadata and errors.
