import assert from "node:assert/strict";
import { test } from "node:test";
import { gzipSync } from "node:zlib";
import { build } from "esbuild";
import { chromium } from "playwright-core";
import { binary, page, publication, server } from "./server.mjs";

test("browser package integration", async (t) => {
  const bundle = await build({
    stdin: { contents: 'export * from "@neverblink/ohi";', resolveDir: process.cwd() },
    bundle: true, platform: "browser", format: "esm", write: false,
  });
  const browser = await chromium.launch(process.env.OHI_CHROME_PATH ?
    { executablePath: process.env.OHI_CHROME_PATH } : { channel: "chrome" });
  t.after(() => browser.close());
  const context = await browser.newContext();
  const browserPage = await context.newPage();
  const app = await server(t, (req, res) => {
    if (req.url === "/file") {
      res.end(binary);
      return;
    }
    res.setHeader("Content-Type", req.url === "/bundle.js" ? "text/javascript" : "text/html");
    res.end(req.url === "/bundle.js" ? bundle.outputFiles[0].contents : "<!doctype html><title>Ohi browser test</title>");
  });
  const fixture = await server(t, (req, res) => {
    if (req.url !== "/denied") res.setHeader("Access-Control-Allow-Origin", app.url);
    if (req.url.startsWith("/search?")) {
      res.end(JSON.stringify({ ...page([`${fixture.url}/metadata?signature=a%2Bb`]),
        facet_counts: [{ field_name: "licenses", counts: [{ value: "MIT", count: 2 }] }],
      }));
    } else if (req.url.startsWith("/metadata?")) {
      res.end(publication);
    } else {
      res.setHeader("Content-Encoding", "gzip");
      res.end(gzipSync(binary));
    }
  });
  await browserPage.goto(app.url);

  await t.test("root lock codecs preserve revision 2 hashes through browser host storage", async () => {
    const result = await browserPage.evaluate(async () => {
      const { decodeLock, encodeLock } = await import("/bundle.js");
      const lock = decodeLock('{"format_version":2,"selections":[{"base_url":"urn:ohi:z:1","url":"https://Files.example/z%2F.ttl?x=1#file"}],"files":[{"base_url":"urn:ohi:z:1","url":"https://Files.example/z%2F.ttl?x=1#file","format":"owl-turtle","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}');
      sessionStorage.setItem("ohi.lock", encodeLock(lock));
      return decodeLock(sessionStorage.getItem("ohi.lock"));
    });
    assert.deepEqual(result, { format_version: 2,
      selections: [{ base_url: "urn:ohi:z:1", url: "https://Files.example/z%2F.ttl?x=1#file" }],
      files: [{ base_url: "urn:ohi:z:1", url: "https://Files.example/z%2F.ttl?x=1#file", format: "owl-turtle",
        sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }],
    });
  });

  await t.test("bundled search, inspect and get preserve metadata and decompressed file bytes", async () => {
    const result = await browserPage.evaluate(async (url) => {
      const { CatalogClient } = await import("/bundle.js");
      const client = new CatalogClient({ search_url: `${url}/search`, query_by: "name,description" });
      const result = await client.search({ q: "*", facet_by: "licenses" });
      const publication = await client.inspect(result.hits[0].base_url);
      const bytes = await client.get(`${url}/file`);
      return { result, title: publication.title, published_at: publication.published_at,
        size: publication.artifacts[0].size_bytes, bytes: [...bytes] };
    }, fixture.url);
    assert.deepEqual(result.result, { hits: [{ base_url: `${fixture.url}/metadata?signature=a%2Bb`,
      namespace: "example", name: "sensors", version: "2026.1+amendment", title: "Sensors",
      description: "Sensor observations and devices.",
      formats: ["ossie-yaml", "owl-rdfxml", "linkml-yaml", "owl-turtle", "rdfs-turtle"],
      licenses: ["MIT", "Apache-2.0"], published_at: "2026-10-13T14:30:00+02:00",
    }], page: 1, per_page: 10, total: 3, search_cutoff: false,
    facet_counts: [{ field_name: "licenses", counts: [{ value: "MIT", count: 2 }] }] });
    assert.equal(result.title, "Sensors");
    assert.equal(result.published_at, "2026-10-13T12:30:00Z");
    assert.equal(result.size, 42);
    assert.deepEqual(result.bytes, [0, 255, 128, 65]);
    assert.deepEqual(fixture.requests.map((req) => req.url), [
      "/search?q=*&query_by=name%2Cdescription&page=1&per_page=10&facet_by=licenses", "/metadata?signature=a%2Bb", "/file",
    ]);
  });

  await t.test("same-origin get omits a stored session cookie", async () => {
    await context.addCookies([{ name: "session", value: "private", url: app.url }]);
    const bytes = await browserPage.evaluate(async (url) => {
      const { CatalogClient } = await import("/bundle.js");
      await (await fetch(url)).arrayBuffer();
      return [...await new CatalogClient().get(url)];
    }, `${app.url}/file`);
    const requests = app.requests.filter((req) => req.url === "/file");
    assert.deepEqual(bytes, [0, 255, 128, 65]);
    assert.equal(requests.length, 2);
    assert.equal(requests[0].headers.cookie, "session=private");
    assert.equal(requests[1].headers.cookie, undefined);
  });

  await t.test("denied CORS produces a connection failure without a fabricated status", async () => {
    const result = await browserPage.evaluate(async (url) => {
      const { CatalogClient, CatalogException } = await import("/bundle.js");
      try {
        await new CatalogClient().get(url);
        return { unexpectedSuccess: true };
      } catch (error) {
        return { typed: error instanceof CatalogException, error: error.error };
      }
    }, `${fixture.url}/denied`);
    assert.equal(result.typed, true);
    assert.equal(result.error.code, "CONNECTION_ERROR");
    assert.equal(result.error.operation, "GET");
    assert.equal(result.error.url, `${fixture.url}/denied`);
    assert.equal("status" in result.error, false);
  });
});
