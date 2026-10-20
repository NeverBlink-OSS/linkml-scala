import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { beforeEach, test } from "node:test";
import { decodePublication } from "../dist/publication.js";

function example(name = "sensors") {
  return JSON.parse(readFileSync(new URL(`../../examples/${name}-package.json`, import.meta.url), "utf8"));
}

const decode = (value) => decodePublication(JSON.stringify(value));
const reject = (value) => assert.throws(() => decode(value), Error);
let data;
beforeEach(() => { data = example(); });

test("maps publication fields", () => {
  const publication = decode(data);
  assert.equal(publication.schema_version, 2);
  assert.equal(publication.base_url, "https://example.org/packages/sensors/1.0.0/");
  assert.equal(publication.version, "1.0.0");
  assert.equal(publication.namespace, "example");
  assert.equal(publication.name, "sensors");
  assert.equal(publication.title, "Sensors");
  assert.equal(publication.published_at, "2026-10-13T12:30:00Z");
  assert.equal(publication.publisher, "Example Project");
  assert.deepEqual(publication.licenses, ["Apache-2.0"]);
  assert.equal(publication.namespace_iri, "https://example.org/sensors#");
  assert.equal(publication.preferred_prefix, "sensors");
  assert.equal(publication.description, "Sensors and their observations.");
  assert.deepEqual(publication.keywords, ["sensors", "observations"]);
  assert.deepEqual(publication.artifacts, [
    {
      format: "owl-turtle", origin: "UPSTREAM",
      url: "https://example.org/packages/sensors/1.0.0/sensors.ttl",
      roots: ["https://example.org/packages/sensors/1.0.0/sensors.ttl"],
      size_bytes: 42, sha256: "0123456789abcdef".repeat(4),
    },
    {
      format: "linkml-yaml", origin: "GENERATED",
      url: "https://example.org/packages/sensors/1.0.0/sensors.yaml",
      roots: ["https://example.org/packages/sensors/1.0.0/sensors.yaml"],
      size_bytes: 42, sha256: "0123456789abcdef".repeat(4),
    },
  ]);
  assert.deepEqual(publication.dependencies, [{ base_url: "https://example.org/packages/units/2.0.0/" }]);
});

test("accepts no dependencies", () => {
  const publication = decode(example("units"));
  assert.equal(publication.base_url, "https://example.org/packages/units/2.0.0/");
  assert.equal(publication.artifacts[0].url, "https://example.org/packages/units/2.0.0/units.ttl");
  assert.deepEqual(publication.dependencies, []);
});

test("accepts empty keywords", () => {
  assert.deepEqual(decode(example("units")).keywords, []);
});

for (const field of ["namespace", "keywords", "dependencies"]) {
  test(`requires publication ${field}`, () => {
    delete data[field];
    reject(data);
  });
}

test("requires artifact roots despite publication roots", () => {
  data.roots = data.artifacts[0].roots;
  delete data.artifacts[0].roots;
  reject(data);
});

test("requires dependency base despite file metadata", () => {
  data.dependencies[0] = { url: "https://example.org/units.ttl" };
  reject(data);
});

for (const value of [null, 7]) {
  test(`rejects namespace ${JSON.stringify(value)}`, () => {
    data.namespace = value;
    reject(data);
  });
}

test("rejects scalar keywords", () => {
  data.keywords = "sensors";
  reject(data);
});

test("rejects a nonobject publication", () => {
  reject([]);
});

test("rejects a nonobject artifact", () => {
  data.artifacts[0] = null;
  reject(data);
});

test("rejects a nonobject dependency", () => {
  data.dependencies[0] = "units";
  reject(data);
});

test("rejects empty artifacts", () => {
  data.artifacts = [];
  reject(data);
});

test("rejects empty artifact roots", () => {
  data.artifacts[0].roots = [];
  reject(data);
});

test("rejects blank namespace", () => {
  data.namespace = " \t\n";
  reject(data);
});

test("preserves opaque version labels", () => {
  data.version = " release + amendment ";
  assert.equal(decode(data).version, " release + amendment ");
});

test("rejects an unsupported revision", () => {
  data.schema_version = 3;
  reject(data);
});

test("rejects the former revision instead of migrating metadata", () => {
  data.schema_version = 1;
  reject(data);
});

test("omits absent namespace metadata", () => {
  const publication = decode(example("units"));
  assert.equal("namespace_iri" in publication, false);
  assert.equal("preferred_prefix" in publication, false);
});

test("requires namespace IRI when a preferred prefix is supplied", () => {
  delete data.namespace_iri;
  reject(data);
});

test("rejects malformed publication time", () => {
  data.published_at = "2026-13-13T12:30:00Z";
  reject(data);
});

test("preserves supplied timestamp precision and offset", () => {
  data.published_at = "2026-10-13T12:30:00.123456789+00:00";
  assert.equal(decode(data).published_at, "2026-10-13T12:30:00.123456789+00:00");
});

test("accepts the largest exactly representable artifact integer", () => {
  data.artifacts[0].size_bytes = 9007199254740991;
  assert.equal(decode(data).artifacts[0].size_bytes, 9007199254740991);
});

test("rejects an unsafe artifact integer", () => {
  data.artifacts[0].size_bytes = 9007199254740992;
  reject(data);
});

for (const value of ["1", true]) {
  test(`rejects nonnumeric revision ${JSON.stringify(value)}`, () => {
    data.schema_version = value;
    reject(data);
  });
}

test("rejects an unknown format", () => {
  data.artifacts[0].format = "future-format";
  reject(data);
});

test("rejects an unknown origin", () => {
  data.artifacts[0].origin = "FUTURE";
  reject(data);
});

test("discards extras at each record level", () => {
  data.package = "obsolete";
  data.roots = ["obsolete"];
  data.artifacts[0].dependencies = "obsolete";
  data.dependencies[0].url = "obsolete";
  const publication = decode(data);
  assert.equal("package" in publication, false);
  assert.equal("roots" in publication, false);
  assert.equal("dependencies" in publication.artifacts[0], false);
  assert.equal("url" in publication.dependencies[0], false);
  assert.equal(publication.namespace, "example");
  assert.equal(publication.artifacts[0].url, "https://example.org/packages/sensors/1.0.0/sensors.ttl");
  assert.equal(publication.dependencies[0].base_url, "https://example.org/packages/units/2.0.0/");
});

test("rejects an invalid dependency URI beside extras", () => {
  data.backend = true;
  data.dependencies[0].base_url = "https://example.org/a b";
  assert.throws(() => decode(data), (error) => {
    assert.ok(error instanceof Error);
    assert.match(error.message, /base_url/);
    return true;
  });
});

test("accepts HTTP development references", () => {
  const publication = decodePublication(JSON.stringify(data).replaceAll("https://example.org", "http://localhost:8080"));
  assert.equal(publication.base_url, "http://localhost:8080/packages/sensors/1.0.0/");
  assert.equal(publication.artifacts[0].url, "http://localhost:8080/packages/sensors/1.0.0/sensors.ttl");
  assert.deepEqual(publication.artifacts[0].roots, ["http://localhost:8080/packages/sensors/1.0.0/sensors.ttl"]);
  assert.equal(publication.dependencies[0].base_url, "http://localhost:8080/packages/units/2.0.0/");
});

test("accepts native URI schemes", () => {
  data.base_url = "urn:ohi:sensors:1";
  assert.equal(decode(data).base_url, "urn:ohi:sensors:1");
});

test("preserves URI spelling", () => {
  data.base_url = "https://Example.org/publication?revision=1#entry";
  assert.equal(decode(data).base_url, "https://Example.org/publication?revision=1#entry");
});

test("rejects a relative publication base", () => {
  data.base_url = "relative.ttl";
  reject(data);
});

test("rejects duplicate artifact URLs", () => {
  data.artifacts[1].url = data.artifacts[0].url;
  reject(data);
});

test("accepts repeated artifact formats", () => {
  data.artifacts[1].format = "owl-turtle";
  data.artifacts.reverse();
  assert.deepEqual(decode(data).artifacts.map(({ url }) => url), [
    "https://example.org/packages/sensors/1.0.0/sensors.yaml",
    "https://example.org/packages/sensors/1.0.0/sensors.ttl",
  ]);
});

test("rejects duplicate dependency bases", () => {
  data.dependencies.push({ base_url: "https://example.org/packages/units/2.0.0/", url: "ignored" });
  reject(data);
});

test("rejects self dependency", () => {
  data.dependencies[0].base_url = "https://example.org/packages/sensors/1.0.0/";
  reject(data);
});

test("preserves dependency identity and order", () => {
  data.dependencies = [
    { base_url: "https://example.org/units" }, { base_url: "https://Example.org/units" },
  ];
  assert.deepEqual(decode(data).dependencies.map(({ base_url }) => base_url),
    ["https://example.org/units", "https://Example.org/units"]);
});

test("preserves artifact roots exactly", () => {
  data.artifacts[0].roots = ["https://example.org/b.ttl", "https://example.org/a.ttl", "https://example.org/b.ttl"];
  assert.deepEqual(decode(data).artifacts[0].roots,
    ["https://example.org/b.ttl", "https://example.org/a.ttl", "https://example.org/b.ttl"]);
});

test("rejects malformed JSON", () => {
  assert.throws(() => decodePublication("{"), Error);
});
