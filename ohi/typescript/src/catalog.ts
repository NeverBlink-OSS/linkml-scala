import type { Publication } from "./generated.js";
import type { CatalogConfig } from "./generated_config.js";
import type { CatalogError, CatalogOperation } from "./generated_error.js";
import type { SearchRequest } from "./generated_search_request.js";
import type { FacetCount, SearchHit, SearchResult } from "./generated_search.js";
import { decodePublication } from "./publication.js";

const searchPath = "/search/collections/packages/documents/search";
const referencePattern = /^([^/@\p{White_Space}`]+)\/([^/@\p{White_Space}`]+)(?:@([^/@\p{White_Space}+`]+))?$/u;

export class CatalogException extends Error {
  constructor(readonly error: CatalogError, options?: ErrorOptions) {
    super(error.message, options);
    this.name = "CatalogException";
  }
}

function httpUrl(value: string, operation: CatalogOperation): URL {
  try {
    const url = new URL(value);
    if (!(url.protocol === "http:" || url.protocol === "https:") || !url.hostname || url.username || url.password) {
      throw new TypeError("Invalid HTTP(S) URL.");
    }
    return url;
  } catch (cause) {
    throw new CatalogException({ code: "INVALID_INPUT", operation, url: value,
      message: "Supply an absolute HTTP(S) URL with a host and no credentials." }, { cause });
  }
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function json(bytes: Uint8Array): unknown {
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
}

function searchResult(bytes: Uint8Array, page: number, perPage: number): SearchResult {
  const raw = json(bytes);
  if (!object(raw) || "grouped_hits" in raw || !Array.isArray(raw.hits) || !object(raw.request_params)) {
    throw new Error("Expected an ungrouped search response with hits and request_params.");
  }
  const result: SearchResult = {
    hits: raw.hits.map((hit: unknown) => {
      if (!object(hit) || !object(hit.document)) throw new Error("Expected a search hit document.");
      return hit.document as SearchHit;
    }),
    total: raw.found as number,
    page: raw.page as number,
    per_page: raw.request_params.per_page as number,
    search_cutoff: raw.search_cutoff as boolean,
    ...(raw.facet_counts === undefined ? {} : { facet_counts: raw.facet_counts as FacetCount[] }),
  };
  if (result.page !== page || result.per_page !== perPage || result.hits.length > perPage) {
    throw new Error("Search response does not match the requested page and page size.");
  }
  return {
    ...result,
    hits: result.hits.map(({ base_url, namespace, name, version, title, description, formats, licenses, published_at, keywords }) =>
      ({ base_url, namespace, name, version, title, description, formats, licenses, published_at,
        ...(keywords === undefined ? {} : { keywords }) })),
    ...(result.facet_counts === undefined ? {} : {
      facet_counts: result.facet_counts.map(({ field_name, counts }) =>
        ({ field_name, counts: counts.map(({ value, count }) => ({ value, count })) })),
    }),
  };
}

/** HTTP operations. Omitting both search settings selects ohi.dev. */
export class CatalogClient {
  private readonly config: CatalogConfig;
  private readonly timeoutMs: number;
  private readonly base: string;
  private readonly latestUrl: string;

  constructor(config: CatalogConfig = {}) {
    const url = config.search_url;
    if ((config.search_url === undefined) !== (config.query_by === undefined)) {
      throw new CatalogException({ code: "INVALID_INPUT", operation: "CONFIGURE",
        ...(url === undefined ? {} : { url }), message: "Supply both search_url and query_by, or omit both." });
    }
    if (url !== undefined) {
      httpUrl(url, "CONFIGURE");
      if (url.includes("?") || url.includes("#")) {
        throw new CatalogException({ code: "INVALID_INPUT", operation: "CONFIGURE", url,
          message: "Supply a search_url without a query or fragment." });
      }
      if (!url.endsWith(searchPath)) {
        throw new CatalogException({ code: "INVALID_INPUT", operation: "CONFIGURE", url,
          message: `Supply a search_url that ends with ${searchPath}.` });
      }
    }
    this.config = {
      ...config,
      search_url: config.search_url ?? "https://api.ohi.dev/search/collections/packages/documents/search",
      query_by: config.query_by ?? "title,name,description,namespace",
    };
    this.timeoutMs = config.timeout_ms ?? 30000;
    this.base = this.config.search_url!.slice(0, -searchPath.length);
    this.latestUrl = `${this.base}/search/collections/packages_latest/documents/search`;
  }

  async search(request: SearchRequest): Promise<SearchResult> {
    const endpoint = request.all_releases ? this.config.search_url! : this.latestUrl;
    const page = request.page ?? 1;
    const perPage = request.per_page ?? 10;
    const url = new URL(endpoint);
    url.searchParams.set("q", request.q);
    url.searchParams.set("query_by", this.config.query_by!);
    url.searchParams.set("page", String(page));
    url.searchParams.set("per_page", String(perPage));
    if (request.filter_by !== undefined) url.searchParams.set("filter_by", request.filter_by);
    if (request.sort_by !== undefined) url.searchParams.set("sort_by", request.sort_by);
    if (request.facet_by !== undefined) url.searchParams.set("facet_by", request.facet_by);
    if (request.max_facet_values !== undefined) url.searchParams.set("max_facet_values", String(request.max_facet_values));
    const bytes = await this.request(url, "SEARCH", endpoint);
    try {
      return searchResult(bytes, page, perPage);
    } catch (cause) {
      throw new CatalogException({ code: "INVALID_RESPONSE", operation: "SEARCH", url: endpoint, status: 200,
        message: "The search response is invalid." }, { cause });
    }
  }

  /** Return the search hit for the latest version of `namespace/name`, or undefined when the catalog has no such
   * ontology.
   */
  async latest(reference: string): Promise<SearchHit | undefined> {
    const parts = referencePattern.exec(reference);
    if (parts === null || parts[3] !== undefined) {
      throw new CatalogException({ code: "INVALID_INPUT", operation: "SEARCH", url: reference,
        message: "Supply an ontology reference namespace/name without a version." });
    }
    const [, namespace, name] = parts;
    const filter = `namespace:=\`${namespace}\` && name:=\`${name}\``;
    const result = await this.search({ q: "*", filter_by: filter });
    // The := filter ignores case and separators, so look-alikes such as w3c/DCAT can match.
    return result.hits.find((hit) => hit.namespace === namespace && hit.name === name);
  }

  /** Fetch the publication at an absolute HTTP(S) URL, of the release `namespace/name@version`, or of the latest
   * version of `namespace/name`.
   */
  async inspect(baseUrl: string): Promise<Publication> {
    let url = baseUrl;
    const parts = referencePattern.exec(baseUrl);
    if (parts !== null) {
      const [, namespace, name, version] = parts;
      if (version !== undefined) {
        url = `${this.base}/files/packages/${[namespace, name, version].map(encodeURIComponent).join("/")}/`;
      } else {
        const hit = await this.latest(baseUrl);
        if (hit === undefined) {
          throw new CatalogException({ code: "HTTP_ERROR", operation: "INSPECT", url: baseUrl, status: 404,
            message: `No ontology matches ${baseUrl}.` });
        }
        url = hit.base_url;
      }
    } else if (!/^https?:\/\//i.test(baseUrl)) {
      // new URL repairs "http:/host" and strips tabs, so require the literal prefix before parsing.
      throw new CatalogException({ code: "INVALID_INPUT", operation: "INSPECT", url: baseUrl,
        message: "Supply an absolute HTTP(S) URL or an ontology reference namespace/name[@version]." });
    }
    const bytes = await this.request(httpUrl(url, "INSPECT"), "INSPECT", url);
    try {
      return decodePublication(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    } catch (cause) {
      throw new CatalogException({ code: "INVALID_RESPONSE", operation: "INSPECT", url, status: 200,
        message: "The publication metadata is invalid." }, { cause });
    }
  }

  async get(url: string): Promise<Uint8Array> {
    return this.request(httpUrl(url, "GET"), "GET", url);
  }

  private async request(target: URL, operation: CatalogOperation, url: string): Promise<Uint8Array> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let response: Response | undefined;
    const transferError = (cause: unknown) => new CatalogException({
      code: controller.signal.aborted ? "TIMEOUT" : "CONNECTION_ERROR", operation, url,
      ...(response === undefined ? {} : { status: response.status }),
      message: controller.signal.aborted ? "The request exceeded timeout_ms." : "The HTTP transfer failed.",
    }, { cause });
    try {
      try {
        response = await fetch(target, { signal: controller.signal, credentials: "omit", redirect: "error" });
      } catch (cause) {
        throw transferError(cause);
      }
      if (response.status !== 200) {
        let responseJson: Record<string, unknown> | undefined;
        try {
          const value = json(new Uint8Array(await response.arrayBuffer()));
          if (object(value)) responseJson = value;
        } catch {
          // A known failure status takes precedence over an unreadable error body.
        }
        throw new CatalogException({ code: "HTTP_ERROR", operation, url, status: response.status,
          ...(responseJson === undefined ? {} : { response_json: responseJson }),
          message: `HTTP ${response.status} for ${url}` });
      }
      try {
        return new Uint8Array(await response.arrayBuffer());
      } catch (cause) {
        throw transferError(cause);
      }
    } finally {
      clearTimeout(timer);
      controller.abort();
    }
  }
}
