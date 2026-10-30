import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import { once } from "node:events";
import { CatalogClient, CatalogException } from "@neverblink/ohi";
import { binary, page, publication, server } from "./server.mjs";

function failure(code, operation, url, status) {
  return (error) => {
    assert.ok(error instanceof CatalogException);
    assert.equal(error.message, error.error.message);
    assert.equal(error.error.code, code);
    assert.equal(error.error.operation, operation);
    assert.equal(error.error.url, url);
    assert.equal(error.error.status, status);
    if (status === undefined) assert.equal("status" in error.error, false);
    if (url === undefined) assert.equal("url" in error.error, false);
    return true;
  };
}

test("omitted search settings use production catalog and preserve timeout", async (t) => {
  const fetched = t.mock.method(globalThis, "fetch", async () =>
    new Response(JSON.stringify(page([], 1, 10, 0))));
  const scheduled = t.mock.method(globalThis, "setTimeout");
  const result = await new CatalogClient({ timeout_ms: 275 }).search({ q: "*" });
  assert.equal(fetched.mock.calls.length, 1);
  const url = fetched.mock.calls[0].arguments[0];
  assert.equal(`${url.origin}${url.pathname}`, "https://api.ohi.dev/search/collections/packages/documents/search");
  assert.deepEqual(Object.fromEntries(url.searchParams), {
    q: "*", query_by: "title,name,description,namespace", page: "1", per_page: "10",
  });
  assert.equal(scheduled.mock.calls[0].arguments[1], 275);
  assert.deepEqual(result, { hits: [], total: 0, page: 1, per_page: 10, search_cutoff: false });
});

for (const [name, config] of Object.entries({
  "endpoint without fields": { search_url: "http://localhost/search" },
  "fields without endpoint": { query_by: "name" },
  "endpoint query": { search_url: "http://localhost/search?q=a", query_by: "name" },
  "endpoint fragment": { search_url: "http://localhost/search#x", query_by: "name" },
  "empty endpoint query": { search_url: "http://localhost/search?", query_by: "name" },
})) {
  test(`configuration rejects ${name}`, () => {
    assert.throws(() => new CatalogClient(config), failure("INVALID_INPUT", "CONFIGURE", config.search_url));
  });
}

for (const url of ["urn:ohi:one", "/relative", "https://user:secret@example.org/file"]) {
  test(`HTTP boundary rejects ${url}`, async () => {
    await assert.rejects(new CatalogClient().get(url), failure("INVALID_INPUT", "GET", url));
  });
}

test("search encodes original values once and preserves complete summaries in backend order", async (t) => {
  const wire = page(["https://example.org/z", "https://example.org/a", "https://example.org/z"], 2, 3, 7);
  wire.hits[1].document.licenses = [];
  wire.hits[0].document.keywords = ["sensors", "observations"];
  wire.hits[1].document.keywords = [];
  const fixture = await server(t, (_, res) => res.end(JSON.stringify(wire)));
  const config = { search_url: `${fixture.url}/collections/packages/search`, query_by: "name,description" };
  const client = new CatalogClient(config);
  config.search_url = "http://invalid.invalid/";
  config.query_by = "mutated";
  const result = await client.search({ q: " µ+& sensors ", page: 2, per_page: 3,
    filter_by: " namespace:=`a+b&c` ", sort_by: "published_at:desc,name:asc", facet_by: " licenses,µ+&formats ",
    max_facet_values: 30 });
  assert.deepEqual(result, {
    hits: ["https://example.org/z", "https://example.org/a", "https://example.org/z"].map((base_url, index) => ({
      base_url, namespace: "example", name: "sensors", version: "2026.1+amendment", title: "Sensors",
      description: "Sensor observations and devices.",
      formats: ["ossie-yaml", "owl-rdfxml", "linkml-yaml", "owl-turtle", "rdfs-turtle"],
      licenses: index === 1 ? [] : ["MIT", "Apache-2.0"], published_at: "2026-10-13T14:30:00+02:00",
      ...[{ keywords: ["sensors", "observations"] }, { keywords: [] }, {}][index],
    })),
    total: 7, page: 2, per_page: 3, search_cutoff: false,
  });
  assert.equal(fixture.requests.length, 1);
  const sent = new URL(fixture.requests[0].url, fixture.url);
  assert.equal(sent.pathname, "/collections/packages/search");
  assert.deepEqual(Object.fromEntries(sent.searchParams), { q: " µ+& sensors ", query_by: "name,description",
    page: "2", per_page: "3", filter_by: " namespace:=`a+b&c` ", sort_by: "published_at:desc,name:asc",
    facet_by: " licenses,µ+&formats ", max_facet_values: "30" });
});

for (const [name, request, expectedPage, expectedSize, hits] of [
  ["defaults", { q: "*" }, 1, 10, []],
  ["minimum page size", { q: "*", per_page: 1 }, 1, 1, []],
  ["maximum page size", { q: "*", per_page: 250 }, 1, 250, []],
  ["full first page", { q: "*", per_page: 2 }, 1, 2, ["https://example.org/a", "https://example.org/b"]],
  ["short second page", { q: "*", page: 2, per_page: 2 }, 2, 2, ["https://example.org/c"]],
  ["empty page past end", { q: "*", page: 3, per_page: 2 }, 3, 2, []],
]) {
  test(`search accepts ${name}`, async (t) => {
    const fixture = await server(t, (_, res) => res.end(JSON.stringify(page(hits, expectedPage, expectedSize))));
    const result = await new CatalogClient({ search_url: fixture.url, query_by: "name" }).search(request);
    assert.deepEqual(result.hits.map((hit) => hit.base_url), hits);
    assert.equal(result.total, 3);
    assert.equal(result.page, expectedPage);
    assert.equal(result.per_page, expectedSize);
    assert.equal(fixture.requests.length, 1);
    const sent = new URL(fixture.requests[0].url, fixture.url);
    assert.equal(sent.searchParams.get("page"), String(expectedPage));
    assert.equal(sent.searchParams.get("per_page"), String(expectedSize));
    assert.equal(sent.searchParams.has("max_facet_values"), false);
  });
}

test("search accepts an early cutoff with a short capped page", async (t) => {
  const fixture = await server(t, (_, res) => res.end(JSON.stringify({ ...page([], 1, 10, 100), search_cutoff: true })));
  const result = await new CatalogClient({ search_url: fixture.url, query_by: "name" }).search({ q: "*" });
  assert.deepEqual(result, { hits: [], page: 1, per_page: 10, total: 100, search_cutoff: true });
  assert.equal(fixture.requests.length, 1);
});

test("search preserves ordered facet values and safe counts while discarding nested extras", async (t) => {
  const wire = { ...page(), facet_counts: [
    { field_name: "licenses", counts: [
      { value: "MIT", count: 0, highlighted: "ignored" },
      { value: "Apache-2.0", count: 9007199254740991, highlighted: "ignored" },
    ], stats: { total_values: 2 } },
    { field_name: "formats", counts: [], stats: {} },
  ] };
  const fixture = await server(t, (_, res) => res.end(JSON.stringify(wire)));
  const result = await new CatalogClient({ search_url: fixture.url, query_by: "name" }).search({ q: "*" });
  assert.deepEqual(result.facet_counts, [
    { field_name: "licenses", counts: [{ value: "MIT", count: 0 }, { value: "Apache-2.0", count: 9007199254740991 }] },
    { field_name: "formats", counts: [] },
  ]);
});

test("search preserves an explicitly empty facet list", async (t) => {
  const fixture = await server(t, (_, res) => res.end(JSON.stringify({ ...page(), facet_counts: [] })));
  const result = await new CatalogClient({ search_url: fixture.url, query_by: "name" }).search({ q: "*" });
  assert.deepEqual(result.facet_counts, []);
});

for (const [name, mutate] of Object.entries({
  "nonobject document": (wire) => { wire.hits = [{ document: null }]; },
  "missing request parameters": (wire) => { delete wire.request_params; },
  "nonarray hits": (wire) => { wire.hits = {}; },
  "grouped hits": (wire) => { wire.grouped_hits = []; },
  "page mismatch": (wire) => { wire.page = 2; },
  "page size mismatch": (wire) => { wire.request_params.per_page = 2; },
  "too many hits": (wire) => { wire.hits = page(["urn:a", "urn:b"]).hits; },
  "null facet list": (wire) => { wire.facet_counts = null; },
  "nonarray facet list": (wire) => { wire.facet_counts = {}; },
  "missing facet counts": (wire) => { wire.facet_counts = [{ field_name: "licenses" }]; },
})) {
  test(`search rejects ${name}`, async (t) => {
    const wire = page(["https://example.org/a"], 1, 1);
    mutate(wire);
    const fixture = await server(t, (_, res) => res.end(JSON.stringify(wire)));
    await assert.rejects(new CatalogClient({ search_url: fixture.url, query_by: "name" }).search({ q: "*", per_page: 1 }),
      failure("INVALID_RESPONSE", "SEARCH", fixture.url, 200));
  });
}

test("inspect fetches the exact supplied path and query without following metadata references", async (t) => {
  const fixture = await server(t, (_, res) => res.end(publication));
  const result = await new CatalogClient().inspect(`${fixture.url}/opaque?signature=a%2Bb#entry`);
  assert.equal(result.title, "Sensors");
  assert.equal(result.artifacts[0].size_bytes, 42);
  assert.deepEqual(result.dependencies, [{ base_url: "https://example.org/packages/units/2.0.0/" }]);
  assert.deepEqual(fixture.requests.map((req) => req.url), ["/opaque?signature=a%2Bb"]);
});

test("get returns complete binary bytes and repeats an explicit request", async (t) => {
  const fixture = await server(t, (_, res) => res.end(binary));
  const client = new CatalogClient();
  assert.deepEqual([...await client.get(`${fixture.url}/file?signature=a%2Bb`)], [0, 255, 128, 65]);
  assert.deepEqual([...await client.get(`${fixture.url}/file?signature=a%2Bb`)], [0, 255, 128, 65]);
  assert.deepEqual(fixture.requests.map((req) => req.url), ["/file?signature=a%2Bb", "/file?signature=a%2Bb"]);
});

test("get accepts an empty file", async (t) => {
  const fixture = await server(t, (_, res) => res.end());
  assert.deepEqual([...await new CatalogClient().get(fixture.url)], []);
});

for (const status of [204, 206, 404]) {
  test(`get rejects HTTP ${status}`, async (t) => {
    const fixture = await server(t, (_, res) => { res.writeHead(status); res.end("unavailable"); });
    await assert.rejects(new CatalogClient().get(fixture.url), (error) => {
      failure("HTTP_ERROR", "GET", fixture.url, status)(error);
      assert.equal("response_json" in error.error, false);
      return true;
    });
  });
}

test("HTTP errors preserve nested backend JSON without replacing the SDK category", async (t) => {
  const fixture = await server(t, (_, res) => {
    res.writeHead(503);
    res.end('{"code":"future","details":{"value":null},"recovery":{"arguments":[null,7,{"wait":true}]}}');
  });
  await assert.rejects(new CatalogClient().inspect(fixture.url), (error) => {
    failure("HTTP_ERROR", "INSPECT", fixture.url, 503)(error);
    assert.deepEqual(JSON.parse(JSON.stringify(error.error)).response_json,
      { code: "future", details: { value: null }, recovery: { arguments: [null, 7, { wait: true }] } });
    return true;
  });
});

for (const body of ["{", "[]"]) {
  test(`HTTP error omits invalid object body ${body}`, async (t) => {
    const fixture = await server(t, (_, res) => { res.writeHead(503); res.end(body); });
    await assert.rejects(new CatalogClient().get(fixture.url), (error) => {
      failure("HTTP_ERROR", "GET", fixture.url, 503)(error);
      assert.equal("response_json" in error.error, false);
      return true;
    });
  });
}

for (const operation of ["SEARCH", "INSPECT"]) {
  const wire = operation === "SEARCH" ? JSON.stringify(page(["https://example.org/a"])) : publication;
  const invalidUtf8 = Buffer.from(wire);
  invalidUtf8[invalidUtf8.indexOf("Sensors", invalidUtf8.indexOf('"title"'))] = 0xff;
  for (const [name, bytes] of [["malformed JSON", Buffer.from("{")], ["invalid UTF-8", invalidUtf8]]) {
    test(`${operation} categorizes ${name}`, async (t) => {
      const fixture = await server(t, (_, res) => res.end(bytes));
      const client = new CatalogClient({ search_url: fixture.url, query_by: "name" });
      await assert.rejects(operation === "SEARCH" ? client.search({ q: "*" }) : client.inspect(fixture.url),
        failure("INVALID_RESPONSE", operation, fixture.url, 200));
    });
  }
}

test("inspect rejects metadata the publication decoder rejects", async (t) => {
  const fixture = await server(t, (_, res) => res.end("{}"));
  await assert.rejects(new CatalogClient().inspect(fixture.url), failure("INVALID_RESPONSE", "INSPECT", fixture.url, 200));
});

test("a refused connection fails without a fabricated response status", async () => {
  const listener = createServer();
  listener.listen(0, "127.0.0.1");
  await once(listener, "listening");
  const url = `http://127.0.0.1:${listener.address().port}`;
  await new Promise((resolve) => listener.close(resolve));
  await assert.rejects(new CatalogClient().get(url), failure("CONNECTION_ERROR", "GET", url));
});

test("a truncated successful body fails without returning partial bytes", async (t) => {
  const fixture = await server(t, (_, res) => {
    res.writeHead(200, { "Content-Length": "100", Connection: "close" });
    res.end("partial");
  });
  await assert.rejects(new CatalogClient().get(fixture.url), failure("CONNECTION_ERROR", "GET", fixture.url, 200));
});

test("redirect refusal never requests its target", async (t) => {
  const fixture = await server(t, (_, res) => { res.writeHead(302, { Location: "/target" }); res.end(); });
  await assert.rejects(new CatalogClient().get(`${fixture.url}/redirect`), failure("CONNECTION_ERROR", "GET", `${fixture.url}/redirect`));
  assert.deepEqual(fixture.requests.map((req) => req.url), ["/redirect"]);
});

for (const [status, code] of [[200, "TIMEOUT"], [503, "HTTP_ERROR"]]) {
  test(`stalled HTTP ${status} body yields ${code} and closes the transfer`, { timeout: 3000 }, async (t) => {
    let closed;
    const closure = new Promise((resolve) => { closed = resolve; });
    const fixture = await server(t, (_, res) => {
      res.on("close", closed);
      res.writeHead(status, { "Content-Length": "100" });
      res.write("{");
    });
    await assert.rejects(new CatalogClient({ timeout_ms: 100 }).get(fixture.url), (error) => {
      failure(code, "GET", fixture.url, status)(error);
      assert.equal("response_json" in error.error, false);
      return true;
    });
    await closure;
    assert.equal(fixture.requests.length, 1);
  });
}
