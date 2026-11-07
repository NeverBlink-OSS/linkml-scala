package eu.neverblink.ohi.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches the ohi catalog, inspects publications and downloads their files, over HTTP. Without
 * search settings, it uses the catalog of ohi.dev. A client can be shared between threads.
 */
public final class CatalogClient {
    private static final String SEARCH_PATH = "/search/collections/packages/documents/search";
    private static final String DEFAULT_SEARCH_URL = "https://api.ohi.dev" + SEARCH_PATH;
    private static final String DEFAULT_QUERY_BY = "title,name,description,namespace";
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final Pattern REFERENCE = Pattern.compile(
            "([^/@\\s`]+)/([^/@\\s`]+)(?:@([^/@\\s+`]+))?", Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<String> HIT_FIELDS = List.of(
            "base_url", "namespace", "name", "version", "title", "description", "formats", "licenses",
            "published_at", "keywords");

    private final String searchUrl;
    private final String latestUrl;
    private final String filesUrl;
    private final String queryBy;
    private final long timeoutMs;
    private final HttpClient http;

    /** A client of the catalog of ohi.dev. */
    public CatalogClient() {
        this(new CatalogConfig());
    }

    /**
     * A client with the given settings, which it copies. {@code search_url} and {@code query_by}
     * go together: supply both, or neither for ohi.dev.
     *
     * @throws CatalogException if the settings are invalid
     */
    public CatalogClient(CatalogConfig config) {
        String url = config.getSearchUrl();
        if ((url == null) != (config.getQueryBy() == null)) {
            throw failure(CatalogErrorCode.INVALID_INPUT, CatalogOperation.CONFIGURE, url,
                    "Supply both search_url and query_by, or omit both.");
        }
        if (url != null) {
            httpUrl(url, CatalogOperation.CONFIGURE);
            if (url.contains("?") || url.contains("#")) {
                throw failure(CatalogErrorCode.INVALID_INPUT, CatalogOperation.CONFIGURE, url,
                        "Supply a search_url without a query or fragment.");
            }
            if (!url.endsWith(SEARCH_PATH)) {
                throw failure(CatalogErrorCode.INVALID_INPUT, CatalogOperation.CONFIGURE, url,
                        "Supply a search_url that ends with " + SEARCH_PATH + ".");
            }
        }
        Long timeout = config.getTimeoutMs();
        if (timeout != null && timeout < 1) {
            throw failure(CatalogErrorCode.INVALID_INPUT, CatalogOperation.CONFIGURE, url,
                    "Supply a positive timeout_ms.");
        }
        this.searchUrl = url != null ? url : DEFAULT_SEARCH_URL;
        this.queryBy = url != null ? config.getQueryBy() : DEFAULT_QUERY_BY;
        this.timeoutMs = timeout != null ? timeout : DEFAULT_TIMEOUT_MS;
        String base = searchUrl.substring(0, searchUrl.length() - SEARCH_PATH.length());
        this.latestUrl = base + "/search/collections/packages_latest/documents/search";
        this.filesUrl = base + "/files/packages/";
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * Searches the latest version of each ontology, or all releases with {@code all_releases}.
     *
     * @throws CatalogException if the request fails or the response is invalid
     */
    public SearchResult search(SearchRequest request) {
        String endpoint = Boolean.TRUE.equals(request.getAllReleases()) ? searchUrl : latestUrl;
        long page = request.getPage() != null ? request.getPage() : 1;
        long perPage = request.getPerPage() != null ? request.getPerPage() : 10;
        StringBuilder query = new StringBuilder();
        parameter(query, "q", request.getQ());
        parameter(query, "query_by", queryBy);
        parameter(query, "page", page);
        parameter(query, "per_page", perPage);
        parameter(query, "filter_by", request.getFilterBy());
        parameter(query, "sort_by", request.getSortBy());
        parameter(query, "facet_by", request.getFacetBy());
        parameter(query, "max_facet_values", request.getMaxFacetValues());
        byte[] body = request(URI.create(endpoint + "?" + query), CatalogOperation.SEARCH, endpoint);
        try {
            return searchResult(body, page, perPage);
        } catch (IOException | IllegalArgumentException e) {
            throw failure(CatalogErrorCode.INVALID_RESPONSE, CatalogOperation.SEARCH, endpoint, 200,
                    "The search response is invalid: " + e.getMessage(), e);
        }
    }

    /**
     * The search hit of the latest version of the ontology {@code namespace/name}, if the catalog
     * has it.
     *
     * @throws CatalogException if the reference has a version, or the search fails
     */
    public Optional<SearchHit> latest(String reference) {
        Matcher parts = REFERENCE.matcher(reference);
        if (!parts.matches() || parts.group(3) != null) {
            throw failure(CatalogErrorCode.INVALID_INPUT, CatalogOperation.SEARCH, reference,
                    "Supply an ontology reference namespace/name without a version.");
        }
        String namespace = parts.group(1);
        String name = parts.group(2);
        SearchRequest request = new SearchRequest();
        request.setQ("*");
        request.setFilterBy("namespace:=`" + namespace + "` && name:=`" + name + "`");
        // The := filter ignores case and separators, so look-alikes such as w3c/DCAT can match
        return search(request).getHits().stream()
                .filter(hit -> hit.getNamespace().equals(namespace) && hit.getName().equals(name))
                .findFirst();
    }

    /**
     * The publication at an absolute HTTP(S) URL, of the release {@code namespace/name@version},
     * or of the latest version of {@code namespace/name}. It doesn't fetch the dependencies.
     *
     * @throws CatalogException if the target is invalid, the request fails or the metadata is
     *     invalid
     */
    public Publication inspect(String target) {
        String url = target;
        Matcher parts = REFERENCE.matcher(target);
        if (parts.matches()) {
            if (parts.group(3) != null) {
                url = filesUrl + Pages.uriComponent(parts.group(1)) + "/"
                        + Pages.uriComponent(parts.group(2)) + "/" + Pages.uriComponent(parts.group(3)) + "/";
            } else {
                url = latest(target).map(SearchHit::getBaseUrl).orElseThrow(() -> failure(
                        CatalogErrorCode.HTTP_ERROR, CatalogOperation.INSPECT, target, 404,
                        "No ontology matches " + target + ".", null));
            }
        } else if (!target.toLowerCase(Locale.ROOT).matches("https?://.*")) {
            throw failure(CatalogErrorCode.INVALID_INPUT, CatalogOperation.INSPECT, target,
                    "Supply an absolute HTTP(S) URL or an ontology reference namespace/name[@version].");
        }
        byte[] body = request(httpUrl(url, CatalogOperation.INSPECT), CatalogOperation.INSPECT, url);
        try {
            return Publications.decode(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw failure(CatalogErrorCode.INVALID_RESPONSE, CatalogOperation.INSPECT, url, 200,
                    "The publication metadata is invalid: " + e.getMessage(), e);
        }
    }

    /**
     * The bytes of the file at an absolute HTTP(S) URL.
     *
     * @throws CatalogException if the URL is invalid or the request fails
     */
    public byte[] get(String url) {
        return request(httpUrl(url, CatalogOperation.GET), CatalogOperation.GET, url);
    }

    private static void parameter(StringBuilder query, String name, Object value) {
        if (value != null) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(name).append('=').append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
        }
    }

    /** The search result in a search response, which has its own shape. */
    private static SearchResult searchResult(byte[] body, long page, long perPage) throws IOException {
        JsonNode raw = Json.MAPPER.readTree(body);
        if (raw == null || !raw.isObject() || raw.has("grouped_hits") || !raw.path("hits").isArray()
                || !raw.path("request_params").isObject()) {
            throw new IllegalArgumentException(
                    "expected an ungrouped search response with hits and request_params");
        }
        ObjectNode result = Json.MAPPER.createObjectNode();
        ArrayNode hits = result.putArray("hits");
        for (JsonNode hit : raw.get("hits")) {
            JsonNode document = hit.path("document");
            if (!document.isObject()) {
                throw new IllegalArgumentException("expected a search hit document");
            }
            // Only the summary fields, as the other SDKs do
            ObjectNode summary = hits.addObject();
            for (String field : HIT_FIELDS) {
                if (document.has(field)) {
                    summary.set(field, document.get(field));
                }
            }
        }
        copy(raw, "found", result, "total");
        copy(raw, "page", result, "page");
        copy(raw.get("request_params"), "per_page", result, "per_page");
        copy(raw, "search_cutoff", result, "search_cutoff");
        if (raw.has("facet_counts")) {
            JsonNode facets = raw.get("facet_counts");
            if (!facets.isArray()) {
                throw new IllegalArgumentException("expected facet_counts to be a list");
            }
            ArrayNode counts = result.putArray("facet_counts");
            for (JsonNode facet : facets) {
                ObjectNode copy = counts.addObject();
                copy(facet, "field_name", copy, "field_name");
                if (facet.path("counts").isArray()) {
                    ArrayNode values = copy.putArray("counts");
                    for (JsonNode value : facet.get("counts")) {
                        ObjectNode count = values.addObject();
                        copy(value, "value", count, "value");
                        copy(value, "count", count, "count");
                    }
                } else {
                    copy(facet, "counts", copy, "counts");
                }
            }
        }
        SearchResult decoded = Json.MAPPER.treeToValue(result, SearchResult.class);
        if (decoded.getPage() != page || decoded.getPerPage() != perPage || decoded.getHits().size() > perPage) {
            throw new IllegalArgumentException("the response does not match the requested page and page size");
        }
        return decoded;
    }

    private static void copy(JsonNode from, String key, ObjectNode to, String as) {
        if (from != null && from.has(key)) {
            to.set(as, from.get(key));
        }
    }

    /** Fetches a URL with one deadline for the whole response, and no redirects. */
    private byte[] request(URI target, CatalogOperation operation, String url) {
        HttpRequest request = HttpRequest.newBuilder(target)
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        AtomicInteger status = new AtomicInteger();
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> {
            status.set(info.statusCode());
            return HttpResponse.BodyHandlers.ofByteArray().apply(info);
        });
        HttpResponse<byte[]> response;
        try {
            response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            Long known = status.get() == 0 ? null : (long) status.get();
            // A known failure status takes precedence over a body that doesn't arrive
            if (known != null && known != 200) {
                throw failure(CatalogErrorCode.HTTP_ERROR, operation, url, known, "HTTP " + known + " for " + url, e);
            }
            throw failure(CatalogErrorCode.TIMEOUT, operation, url, known, "The request exceeded timeout_ms.", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(CatalogErrorCode.CONNECTION_ERROR, operation, url, null, "The request was interrupted.", e);
        } catch (ExecutionException e) {
            Long known = status.get() == 0 ? null : (long) status.get();
            throw failure(CatalogErrorCode.CONNECTION_ERROR, operation, url, known,
                    "The HTTP transfer failed: " + e.getCause(), e.getCause());
        }
        if (response.statusCode() != 200) {
            CatalogException failure = failure(CatalogErrorCode.HTTP_ERROR, operation, url,
                    (long) response.statusCode(), "HTTP " + response.statusCode() + " for " + url, null);
            failure.getError().setResponseJson(jsonObject(response.body()));
            throw failure;
        }
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        if (!encoding.trim().equalsIgnoreCase("identity")) {
            throw failure(CatalogErrorCode.INVALID_RESPONSE, operation, url, 200L,
                    "The response has an unsupported HTTP content encoding.", null);
        }
        return response.body();
    }

    /** The body of a failure response, if it is a JSON object. */
    private static Map<?, ?> jsonObject(byte[] body) {
        try {
            Object value = Json.MAPPER.readValue(body, Object.class);
            return value instanceof Map ? (Map<?, ?>) value : null;
        } catch (IOException e) {
            // The HTTP status matters more than an unreadable body
            return null;
        }
    }

    private static URI httpUrl(String url, CatalogOperation operation) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                throw new URISyntaxException(url, "not an absolute HTTP(S) URL with a host and no credentials");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw failure(CatalogErrorCode.INVALID_INPUT, operation, url, null,
                    "Supply an absolute HTTP(S) URL with a host and no credentials.", e);
        }
    }

    private static CatalogException failure(
            CatalogErrorCode code, CatalogOperation operation, String url, String message) {
        return failure(code, operation, url, null, message, null);
    }

    private static CatalogException failure(CatalogErrorCode code, CatalogOperation operation, String url,
            Number status, String message, Throwable cause) {
        CatalogError error = new CatalogError();
        error.setCode(code);
        error.setOperation(operation);
        error.setUrl(url);
        error.setStatus(status == null ? null : status.longValue());
        error.setMessage(message);
        return new CatalogException(error, cause);
    }
}
