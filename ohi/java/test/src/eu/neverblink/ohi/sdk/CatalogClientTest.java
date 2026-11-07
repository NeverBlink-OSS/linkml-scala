package eu.neverblink.ohi.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogClientTest {
    static final String SEARCH_PATH = "/search/collections/packages/documents/search";
    static final String LATEST_PATH = "/search/collections/packages_latest/documents/search";

    /** What the server answers on a path. With {@code stall}, it sends the body but not all of it. */
    record Route(int status, byte[] body, Map<String, String> headers, boolean stall) {
    }

    /** A request the server got: its path with the query, and its Accept-Encoding. */
    record Request(String path, String encoding) {
    }

    static HttpServer server;
    static String base;
    static final Map<String, Route> routes = new ConcurrentHashMap<>();
    static final List<Request> requests = new CopyOnWriteArrayList<>();
    static volatile CountDownLatch release = new CountDownLatch(1);

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()),
                    exchange.getRequestHeaders().getFirst("Accept-Encoding")));
            Route route = routes.get(exchange.getRequestURI().getRawPath());
            route.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            long length = route.body().length + (route.stall() ? 1 : 0);
            exchange.sendResponseHeaders(route.status(), length == 0 ? -1 : length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(route.body());
                out.flush();
                if (route.stall()) {
                    release.await(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // The client gave up
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        routes.clear();
        requests.clear();
        release = new CountDownLatch(1);
    }

    @AfterEach
    void unstall() {
        release.countDown();
    }

    static String route(String path, byte[] body, int status, Map<String, String> headers, boolean stall) {
        routes.put(path, new Route(status, body, headers, stall));
        return base + path;
    }

    static String route(String path, byte[] body) {
        return route(path, body, 200, Map.of(), false);
    }

    static String route(String path, String body, int status) {
        return route(path, body.getBytes(StandardCharsets.UTF_8), status, Map.of(), false);
    }

    static byte[] example(String name) {
        try {
            return Files.readAllBytes(PublicationsTest.EXAMPLES.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static ObjectNode searchBody(List<String> urls, int page, int perPage, long total) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        ArrayNode hits = body.putArray("hits");
        for (String url : urls) {
            ObjectNode document = hits.addObject().putObject("document");
            document.put("base_url", url).put("namespace", "example").put("name", "sensors")
                    .put("version", "1.2+fix").put("title", "Sensors").put("description", "Sensor observations")
                    .put("published_at", "2026-10-14T10:20:30Z");
            document.putArray("formats").add("owl-turtle");
            document.putArray("licenses");
        }
        body.put("found", total).put("page", page).put("search_cutoff", false);
        body.putObject("request_params").put("per_page", perPage);
        return body;
    }

    static byte[] bytes(Object json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static CatalogClient searchClient() {
        CatalogConfig config = new CatalogConfig();
        config.setSearchUrl(base + SEARCH_PATH);
        config.setQueryBy("name,description");
        return new CatalogClient(config);
    }

    static SearchRequest query(String q) {
        SearchRequest request = new SearchRequest();
        request.setQ(q);
        return request;
    }

    /** The query parameters of a request, in order. */
    static Map<String, String> parameters(Request request) {
        Map<String, String> result = new LinkedHashMap<>();
        String query = request.path().substring(request.path().indexOf('?') + 1);
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    static String path(Request request) {
        int query = request.path().indexOf('?');
        return query < 0 ? request.path() : request.path().substring(0, query);
    }

    static CatalogError assertFailure(Supplier<?> action, CatalogErrorCode code, CatalogOperation operation,
            String url, Integer status) {
        CatalogException failure = assertThrows(CatalogException.class, action::get);
        CatalogError error = failure.getError();
        assertEquals(code, error.getCode());
        assertEquals(operation, error.getOperation());
        assertEquals(url, error.getUrl());
        assertEquals(status == null ? null : (long) status, error.getStatus());
        assertEquals(error.getMessage(), failure.getMessage());
        return error;
    }

    // Configuration

    @Test
    void invalidConfigurationFailsWithoutARequest() {
        List<String[]> settings = List.of(
                new String[] {base, null}, new String[] {null, "name"},
                new String[] {base + "?q=x", "name"}, new String[] {base + "#x", "name"},
                new String[] {base + "/search", "name"}, new String[] {"ftp://host" + SEARCH_PATH, "name"});
        for (String[] setting : settings) {
            CatalogConfig config = new CatalogConfig();
            config.setSearchUrl(setting[0]);
            config.setQueryBy(setting[1]);
            assertFailure(() -> new CatalogClient(config), CatalogErrorCode.INVALID_INPUT,
                    CatalogOperation.CONFIGURE, setting[0], null);
        }
        CatalogConfig timeout = new CatalogConfig();
        timeout.setTimeoutMs(0L);
        assertFailure(() -> new CatalogClient(timeout), CatalogErrorCode.INVALID_INPUT,
                CatalogOperation.CONFIGURE, null, null);
        assertEquals(List.of(), requests);
    }

    @Test
    void copiesTheConfigurationAndUsesDefaultsForSearch() {
        route(LATEST_PATH, bytes(searchBody(List.of(), 1, 10, 0)));
        CatalogConfig config = new CatalogConfig();
        config.setSearchUrl(base + SEARCH_PATH);
        config.setQueryBy("name");
        CatalogClient client = new CatalogClient(config);
        config.setSearchUrl(base + "/changed");
        config.setQueryBy("changed");
        SearchResult result = client.search(query("*"));
        assertEquals(1, result.getPage());
        assertEquals(10, result.getPerPage());
        assertEquals(LATEST_PATH, path(requests.get(0)));
        assertEquals(Map.of("q", "*", "query_by", "name", "page", "1", "per_page", "10"), parameters(requests.get(0)));
        assertEquals("identity", requests.get(0).encoding());
    }

    // Search

    @Test
    void searchesTheLatestVersionsUnlessAllReleasesAndNeverSendsTheOption() {
        route(LATEST_PATH, bytes(searchBody(List.of(), 1, 10, 0)));
        route(SEARCH_PATH, bytes(searchBody(List.of(), 1, 10, 0)));
        CatalogClient client = searchClient();
        client.search(query("*"));
        SearchRequest notAll = query("*");
        notAll.setAllReleases(false);
        client.search(notAll);
        SearchRequest all = query("*");
        all.setAllReleases(true);
        client.search(all);
        assertEquals(List.of(LATEST_PATH, LATEST_PATH, SEARCH_PATH),
                requests.stream().map(CatalogClientTest::path).collect(Collectors.toList()));
        for (Request request : requests) {
            assertEquals(Map.of("q", "*", "query_by", "name,description", "page", "1", "per_page", "10"),
                    parameters(request));
        }
    }

    @Test
    void derivesTheLatestSearchAndReleaseUrlsFromAPrefixedSearchUrl() {
        route("/hub" + LATEST_PATH, bytes(searchBody(List.of(), 1, 10, 0)));
        route("/hub/files/packages/w3c/dcat/2/", example("sensors-package.json"));
        CatalogConfig config = new CatalogConfig();
        config.setSearchUrl(base + "/hub" + SEARCH_PATH);
        config.setQueryBy("name");
        CatalogClient client = new CatalogClient(config);
        client.search(query("*"));
        client.inspect("w3c/dcat@2");
        assertEquals(List.of("/hub" + LATEST_PATH, "/hub/files/packages/w3c/dcat/2/"),
                requests.stream().map(CatalogClientTest::path).collect(Collectors.toList()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 250})
    void acceptsPageSizeLimits(int size) {
        route(LATEST_PATH, bytes(searchBody(List.of(), 1, size, 0)));
        SearchRequest request = query("*");
        request.setPerPage((long) size);
        assertEquals(size, searchClient().search(request).getPerPage());
        assertEquals(String.valueOf(size), parameters(requests.get(0)).get("per_page"));
    }

    @Test
    void encodesParametersOnceAndKeepsTheOrderAndDuplicatesOfHits() {
        ObjectNode body = searchBody(List.of("urn:b", "urn:a", "urn:b"), 2, 3, 3);
        ObjectNode facet = body.putArray("facet_counts").addObject().put("field_name", "formats");
        facet.putArray("counts").addObject().put("value", "owl-turtle").put("count", 2);
        route(LATEST_PATH, bytes(body));
        SearchRequest request = query("温度 + & sensors");
        request.setPage(2L);
        request.setPerPage(3L);
        request.setFilterBy("namespace:=[`a+b`,`c&d`]");
        request.setSortBy("published_at:desc,name:asc");
        request.setFacetBy("namespace,formats(sort_by:_alpha:asc),licenses(a+b&c)");
        request.setMaxFacetValues(30L);
        SearchResult result = searchClient().search(request);
        assertEquals(List.of("urn:b", "urn:a", "urn:b"),
                result.getHits().stream().map(SearchHit::getBaseUrl).collect(Collectors.toList()));
        assertEquals("Sensors", result.getHits().get(0).getTitle());
        assertEquals("formats", result.getFacetCounts().get(0).getFieldName());
        assertEquals(2, result.getFacetCounts().get(0).getCounts().get(0).getCount());
        assertEquals(1, requests.size());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("q", "温度 + & sensors");
        expected.put("query_by", "name,description");
        expected.put("page", "2");
        expected.put("per_page", "3");
        expected.put("filter_by", "namespace:=[`a+b`,`c&d`]");
        expected.put("sort_by", "published_at:desc,name:asc");
        expected.put("facet_by", "namespace,formats(sort_by:_alpha:asc),licenses(a+b&c)");
        expected.put("max_facet_values", "30");
        assertEquals(new ArrayList<>(expected.entrySet()), new ArrayList<>(parameters(requests.get(0)).entrySet()));
    }

    @Test
    void keepsTheSummaryFieldsAndDiscardsUnknownProperties() {
        ObjectNode body = searchBody(List.of("urn:a"), 1, 2, 3);
        body.putObject("future").put("enabled", true);
        ObjectNode hit = (ObjectNode) body.get("hits").get(0);
        hit.put("text_match", 9);
        ObjectNode document = (ObjectNode) hit.get("document");
        document.put("published_at", "2026-10-14T10:20:30.123456+02:30");
        document.putArray("keywords").add("sensors").add("observations");
        document.putObject("future").put("enabled", true);
        ObjectNode facet = body.putArray("facet_counts").addObject().put("field_name", "namespace");
        facet.putObject("stats").put("total_values", 2);
        facet.putArray("counts").addObject().put("value", "z").put("count", 8).put("highlighted", "<mark>z</mark>");
        route(LATEST_PATH, bytes(body));
        SearchRequest request = query("*");
        request.setPerPage(2L);
        SearchResult result = searchClient().search(request);
        SearchHit decoded = result.getHits().get(0);
        assertTrue(decoded.getExtraProperties().isEmpty());
        assertTrue(result.getExtraProperties().isEmpty());
        assertEquals(List.of("sensors", "observations"), decoded.getKeywords());
        assertEquals(OffsetDateTime.of(2026, 10, 14, 10, 20, 30, 123456000, ZoneOffset.ofHoursMinutes(2, 30)),
                decoded.getPublishedAt());
        assertEquals(8, result.getFacetCounts().get(0).getCounts().get(0).getCount());
        assertEquals(3, result.getTotal());
    }

    @Test
    void keepsAbsentAndEmptyFacets() {
        route(LATEST_PATH, bytes(searchBody(List.of(), 1, 10, 0)));
        assertNull(searchClient().search(query("*")).getFacetCounts());
        ObjectNode empty = searchBody(List.of(), 1, 10, 0);
        empty.putArray("facet_counts");
        route(LATEST_PATH, bytes(empty));
        assertEquals(List.of(), searchClient().search(query("*")).getFacetCounts());
    }

    @Test
    void rejectsInvalidSearchResponses() {
        List<ObjectNode> bodies = new ArrayList<>();
        for (String field : List.of("found", "page", "request_params", "search_cutoff", "hits")) {
            ObjectNode body = searchBody(List.of("urn:a"), 1, 10, 3);
            body.remove(field);
            bodies.add(body);
        }
        for (String field : List.of("base_url", "namespace", "name", "version", "title", "description",
                "formats", "licenses", "published_at")) {
            ObjectNode body = searchBody(List.of("urn:a"), 1, 10, 3);
            ((ObjectNode) body.get("hits").get(0).get("document")).remove(field);
            bodies.add(body);
        }
        ObjectNode grouped = searchBody(List.of(), 1, 10, 0);
        grouped.putArray("grouped_hits");
        bodies.add(grouped);
        ObjectNode notDocument = searchBody(List.of(), 1, 10, 0);
        notDocument.withArray("hits").addObject().putArray("document");
        bodies.add(notDocument);
        ObjectNode badFormat = searchBody(List.of("urn:a"), 1, 10, 3);
        ((ObjectNode) badFormat.get("hits").get(0).get("document")).putArray("formats").add("owl");
        bodies.add(badFormat);
        ObjectNode badDate = searchBody(List.of("urn:a"), 1, 10, 3);
        ((ObjectNode) badDate.get("hits").get(0).get("document")).put("published_at", "2026-02-30T10:20:30Z");
        bodies.add(badDate);
        ObjectNode facets = searchBody(List.of(), 1, 10, 0);
        facets.putObject("facet_counts");
        bodies.add(facets);
        bodies.add(searchBody(List.of(), 2, 10, 0));
        bodies.add(searchBody(List.of(), 1, 3, 0));
        bodies.add(searchBody(List.of("urn:a", "urn:b", "urn:c"), 1, 2, 3));
        for (ObjectNode body : bodies) {
            route(LATEST_PATH, bytes(body));
            assertFailure(() -> searchClient().search(query("*")), CatalogErrorCode.INVALID_RESPONSE,
                    CatalogOperation.SEARCH, base + LATEST_PATH, 200);
        }
        route(LATEST_PATH, "{", 200);
        assertFailure(() -> searchClient().search(query("*")), CatalogErrorCode.INVALID_RESPONSE,
                CatalogOperation.SEARCH, base + LATEST_PATH, 200);
    }

    // Inspect and latest

    @Test
    void inspectsAnExactOpaqueUrlWithoutFetchingDependencies() {
        String url = route("/opaque", example("sensors-package.json")) + "?token=a%2Bb";
        Publication publication = new CatalogClient().inspect(url);
        assertEquals("sensors", publication.getName());
        assertEquals("Example Project", publication.getPublisher());
        assertEquals(List.of(new Request("/opaque?token=a%2Bb", "identity")), requests);
    }

    @Test
    void inspectsAReleaseByItsEncodedPathWithoutSearching() {
        String path = "/files/packages/team%3Aa/model%25x/v1.0%C3%A9/";
        route(path, example("sensors-package.json"));
        searchClient().inspect("team:a/model%x@v1.0é");
        assertEquals(List.of(new Request(path, "identity")), requests);
    }

    @Test
    void inspectsTheLatestVersionWithTheExactNamespaceAndName() {
        ObjectNode body = searchBody(List.of(base + "/a/", base + "/b/"), 1, 10, 2);
        ((ObjectNode) body.get("hits").get(0).get("document")).put("name", "SENSORS");
        route(LATEST_PATH, bytes(body));
        route("/b/", example("sensors-package.json"));
        searchClient().inspect("example/sensors");
        assertEquals(LATEST_PATH, path(requests.get(0)));
        assertEquals("namespace:=`example` && name:=`sensors`", parameters(requests.get(0)).get("filter_by"));
        assertEquals("*", parameters(requests.get(0)).get("q"));
        assertEquals(new Request("/b/", "identity"), requests.get(1));
    }

    @Test
    void inspectingAnUnknownOntologyIsHttp404() {
        route(LATEST_PATH, bytes(searchBody(List.of(), 1, 10, 0)));
        assertFailure(() -> searchClient().inspect("w3c/nope"), CatalogErrorCode.HTTP_ERROR,
                CatalogOperation.INSPECT, "w3c/nope", 404);
        assertEquals(1, requests.size());
    }

    @Test
    void rejectsTargetsWithoutARequest() {
        assertFailure(() -> searchClient().inspect("w3c"), CatalogErrorCode.INVALID_INPUT,
                CatalogOperation.INSPECT, "w3c", null);
        assertFailure(() -> searchClient().latest("w3c/dcat@2"), CatalogErrorCode.INVALID_INPUT,
                CatalogOperation.SEARCH, "w3c/dcat@2", null);
        assertEquals(List.of(), requests);
    }

    @Test
    void latestIsEmptyWithoutAnExactMatch() {
        ObjectNode body = searchBody(List.of("urn:a"), 1, 10, 1);
        ((ObjectNode) body.get("hits").get(0).get("document")).put("namespace", "EXAMPLE");
        route(LATEST_PATH, bytes(body));
        assertTrue(searchClient().latest("example/sensors").isEmpty());
    }

    @Test
    void anInvalidPublicationIsAnInvalidResponse() {
        String url = route("/publication", "{}", 200);
        assertFailure(() -> new CatalogClient().inspect(url), CatalogErrorCode.INVALID_RESPONSE,
                CatalogOperation.INSPECT, url, 200);
    }

    // Get

    @Test
    void getsTheBytesEveryTime() {
        byte[] content = new byte[] {0, (byte) 0xff, (byte) 0x80, '\n'};
        String url = route("/file", content);
        CatalogClient client = new CatalogClient();
        assertArrayEquals(content, client.get(url));
        assertArrayEquals(content, client.get(url));
        assertEquals(List.of(new Request("/file", "identity"), new Request("/file", "identity")), requests);
        assertArrayEquals(new byte[0], client.get(route("/empty", new byte[0])));
    }

    @Test
    void rejectsUnsupportedUrlsWithoutARequest() {
        for (String url : List.of("urn:ohi:one", "/relative", "http://user:secret@localhost/a", "https:///a",
                "http://host/a b")) {
            assertFailure(() -> new CatalogClient().get(url), CatalogErrorCode.INVALID_INPUT, CatalogOperation.GET,
                    url, null);
        }
        assertEquals(List.of(), requests);
    }

    @Test
    void keepsTheStatusOfAnHttpFailure() {
        String url = route("/missing", "", 404);
        assertFailure(() -> new CatalogClient().get(url), CatalogErrorCode.HTTP_ERROR, CatalogOperation.GET, url, 404);
    }

    @Test
    void keepsNestedServerJsonAndNullsOfAnHttpFailure() throws IOException {
        String url = route("/unavailable",
                "{\"code\":\"FUTURE\",\"details\":{\"x\":[null,1]},\"recovery\":{\"args\":{\"cursor\":null}}}", 503);
        CatalogError error = assertFailure(() -> new CatalogClient().get(url), CatalogErrorCode.HTTP_ERROR,
                CatalogOperation.GET, url, 503);
        assertEquals(Json.MAPPER.readTree(
                        "{\"code\":\"FUTURE\",\"details\":{\"x\":[null,1]},\"recovery\":{\"args\":{\"cursor\":null}}}"),
                Json.MAPPER.valueToTree(error.getResponseJson()));
    }

    @Test
    void keepsTheHttpFailureWhenItsBodyIsNotAJsonObject() {
        for (byte[] body : List.of("not JSON".getBytes(StandardCharsets.UTF_8), "{".getBytes(StandardCharsets.UTF_8),
                "[]".getBytes(StandardCharsets.UTF_8), new byte[] {'{', '"', 'm', '"', ':', '"', (byte) 0xff, '"', '}'})) {
            String url = route("/error", body, 503, Map.of(), false);
            CatalogError error = assertFailure(() -> new CatalogClient().get(url), CatalogErrorCode.HTTP_ERROR,
                    CatalogOperation.GET, url, 503);
            assertNull(error.getResponseJson());
        }
    }

    @Test
    void aRefusedConnectionHasNoStatus() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port + "/refused";
        assertFailure(() -> new CatalogClient().get(url), CatalogErrorCode.CONNECTION_ERROR, CatalogOperation.GET,
                url, null);
    }

    @Test
    void doesNotFollowRedirects() {
        String url = route("/redirect", new byte[0], 302, Map.of("Location", base + "/target"), false);
        assertFailure(() -> new CatalogClient().get(url), CatalogErrorCode.HTTP_ERROR, CatalogOperation.GET, url, 302);
        assertEquals(List.of(new Request("/redirect", "identity")), requests);
    }

    @Test
    void partialContentIsAnHttpFailure() {
        String url = route("/partial", "partial", 206);
        assertFailure(() -> new CatalogClient().get(url), CatalogErrorCode.HTTP_ERROR, CatalogOperation.GET, url, 206);
    }

    @Test
    void rejectsAContentEncoding() {
        String url = route("/compressed", "coding".getBytes(StandardCharsets.UTF_8), 200,
                Map.of("Content-Encoding", "gzip"), false);
        assertFailure(() -> new CatalogClient().get(url), CatalogErrorCode.INVALID_RESPONSE, CatalogOperation.GET,
                url, 200);
    }

    @Test
    void aStalledBodyTimesOutWithoutReturningPartialBytes() {
        String url = route("/stall", "partial".getBytes(StandardCharsets.UTF_8), 200, Map.of(), true);
        CatalogConfig config = new CatalogConfig();
        config.setTimeoutMs(50L);
        long start = System.nanoTime();
        assertFailure(() -> new CatalogClient(config).get(url), CatalogErrorCode.TIMEOUT, CatalogOperation.GET, url, 200);
        assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(1500));
    }

    @Test
    void aStalledErrorBodyKeepsTheHttpFailure() {
        String url = route("/stall-error", "{\"partial\":".getBytes(StandardCharsets.UTF_8), 503, Map.of(), true);
        CatalogConfig config = new CatalogConfig();
        config.setTimeoutMs(50L);
        CatalogError error = assertFailure(() -> new CatalogClient(config).get(url), CatalogErrorCode.HTTP_ERROR,
                CatalogOperation.GET, url, 503);
        assertNull(error.getResponseJson());
        assertFalse(error.getMessage().isBlank());
    }
}
