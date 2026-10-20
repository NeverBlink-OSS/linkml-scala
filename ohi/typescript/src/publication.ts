import { Ajv2020 } from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import type { Publication } from "./generated.js";
import schema from "./package.schema.json" with { type: "json" };

const ajv = new Ajv2020();
// The CommonJS plugin exposes its callable default under NodeNext.
addFormats.default(ajv, ["uri", "date-time"]);
const validate = ajv.compile<Publication>(schema);

/** Decode publication metadata, validating consumed fields and discarding extras. */
export function decodePublication(text: string): Publication {
  const data: unknown = JSON.parse(text);
  if (!validate(data)) throw new Error(ajv.errorsText(validate.errors));
  if (data.preferred_prefix !== undefined && data.namespace_iri === undefined) {
    throw new Error("preferred_prefix requires namespace_iri");
  }
  if (data.artifacts.some(({ size_bytes }) => !Number.isSafeInteger(size_bytes))) {
    throw new Error("artifact size_bytes exceeds the supported integer range");
  }

  const artifactUrls = data.artifacts.map(({ url }) => url);
  if (new Set(artifactUrls).size !== artifactUrls.length) {
    throw new Error("artifacts must have unique URLs");
  }
  const dependencyUrls = data.dependencies.map(({ base_url }) => base_url);
  if (new Set(dependencyUrls).size !== dependencyUrls.length) {
    throw new Error("dependencies must have unique base_url values");
  }
  if (dependencyUrls.includes(data.base_url)) {
    throw new Error("dependencies must not contain the publication itself");
  }

  return {
    schema_version: data.schema_version,
    base_url: data.base_url,
    version: data.version,
    namespace: data.namespace,
    name: data.name,
    title: data.title,
    published_at: data.published_at,
    publisher: data.publisher,
    licenses: data.licenses,
    ...(data.namespace_iri === undefined ? {} : { namespace_iri: data.namespace_iri }),
    ...(data.preferred_prefix === undefined ? {} : { preferred_prefix: data.preferred_prefix }),
    description: data.description,
    keywords: data.keywords,
    artifacts: data.artifacts.map(({ format, url, origin, roots, size_bytes, sha256 }) =>
      ({ format, url, origin, roots, size_bytes, sha256 })),
    dependencies: data.dependencies.map(({ base_url }) => ({ base_url })),
  };
}
