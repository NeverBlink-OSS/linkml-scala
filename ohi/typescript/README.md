# ohi SDK for TypeScript

Search ontologies on [ohi.dev](https://ohi.dev), inspect their metadata, download selected files and save file selections.

Works with Node 22 or later and browsers with Fetch. Browsers need CORS access to the catalog, publication and artifact endpoints.

```sh
npm install @neverblink/ohi
```

Search, inspect the first hit and download its first file:

```typescript
import { CatalogClient } from "@neverblink/ohi";

const client = new CatalogClient();
const page = await client.search({ q: "sensors", page: 1, per_page: 10 });
if (page.hits.length > 0) {
  const publication = await client.inspect(page.hits[0].base_url);
  const artifact = publication.artifacts[0];
  const content = await client.get(artifact.url);
}
```

`get` returns the file's bytes as a `Uint8Array`. File reading and writing are in `@neverblink/ohi/node`.

**See the [SDK documentation](https://ohi.dev/docs/reference/sdks/)** for configuration, file selections, metadata and errors.
