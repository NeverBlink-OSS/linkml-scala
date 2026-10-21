import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { once } from "node:events";

export const publication = readFileSync(new URL("../../examples/sensors-package.json", import.meta.url));
export const binary = Buffer.from([0, 255, 128, 65]);

export function page(hits = [], page = 1, perPage = 10, found = 3) {
  return {
    hits: hits.map((base_url) => ({ document: {
      base_url, namespace: "example", name: "sensors", version: "2026.1+amendment", title: "Sensors",
      description: "Sensor observations and devices.",
      formats: ["ossie-yaml", "owl-rdfxml", "linkml-yaml", "owl-turtle", "rdfs-turtle"],
      licenses: ["MIT", "Apache-2.0"], published_at: "2026-10-13T14:30:00+02:00", ignored: true,
    } })),
    found, page, request_params: { per_page: perPage }, search_cutoff: false, ignored: true,
  };
}

export async function server(t, handler) {
  const requests = [];
  const fixture = createServer((req, res) => {
    requests.push({ url: req.url, headers: req.headers });
    res.setHeader("Cache-Control", "no-store");
    handler(req, res);
  });
  fixture.listen(0, "127.0.0.1");
  await once(fixture, "listening");
  t.after(async () => {
    fixture.closeAllConnections();
    await new Promise((resolve) => fixture.close(resolve));
  });
  return { url: `http://127.0.0.1:${fixture.address().port}`, requests };
}
