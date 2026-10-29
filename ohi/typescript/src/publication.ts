import type { Publication } from "./generated.js";

/** Decode publication metadata, discarding extras. Field values are not validated. */
export function decodePublication(text: string): Publication {
  const data: Publication = JSON.parse(text);
  if (data.preferred_prefix !== undefined && data.namespace_iri === undefined) {
    throw new Error("preferred_prefix requires namespace_iri");
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
