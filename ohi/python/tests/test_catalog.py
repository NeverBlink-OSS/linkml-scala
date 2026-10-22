import json
import socket
import threading
import time
import unittest
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit

from ohi import CatalogClient, CatalogConfig, CatalogException, FacetCount, FacetValueCount, SearchRequest
from ohi.catalog import _decode_search
from test_publication import EXAMPLES


def search_body(urls=(), page=1, per_page=2, total=3, cutoff=False):
    return {
        "hits": [{"document": {
            "base_url": url, "namespace": "example", "name": "sensors", "version": "1.2+fix",
            "title": "Sensors", "description": "Sensor observations", "formats": ["owl-turtle"],
            "licenses": [], "published_at": "2026-10-14T10:20:30Z",
        }} for url in urls],
        "found": total, "page": page, "request_params": {"per_page": per_page},
        "search_cutoff": cutoff,
    }


class SearchCodecTests(unittest.TestCase):
    def test_maps_full_partial_and_past_end_pages_without_inferred_counts(self):
        for page, urls in ((1, ["urn:b", "urn:a"]), (2, ["urn:c"]), (3, [])):
            with self.subTest(page=page):
                result = _decode_search(json.dumps(search_body(urls, page=page)), page, 2)
                self.assertEqual([hit.base_url for hit in result.hits], urls)
                self.assertEqual((result.total, result.page, result.per_page, result.search_cutoff),
                                 (3, page, 2, False))

    def test_accepts_cutoff_and_capped_short_pages(self):
        for cutoff in (False, True):
            with self.subTest(cutoff=cutoff):
                result = _decode_search(json.dumps(search_body(["urn:a"], cutoff=cutoff)), 1, 2)
                self.assertEqual([hit.base_url for hit in result.hits], ["urn:a"])
                self.assertEqual((result.total, result.search_cutoff), (3, cutoff))

    def test_rejects_missing_consumed_fields(self):
        fields = {
            "response": ("found", "page", "request_params", "search_cutoff", "hits"),
            "hit": ("base_url", "namespace", "name", "version", "title", "description",
                    "formats", "licenses", "published_at"),
        }
        for scope, names in fields.items():
            for field in names:
                with self.subTest(scope=scope, field=field):
                    data = search_body(["urn:a"])
                    target = data if scope == "response" else data["hits"][0]["document"]
                    del target[field]
                    with self.assertRaises(ValueError):
                        _decode_search(json.dumps(data), 1, 2)

    def test_preserves_summary_fields_and_discards_unknown_properties(self):
        data = search_body(["urn:a"])
        data["future"] = {"enabled": True}
        data["hits"][0]["text_match"] = 9
        data["hits"][0]["document"].update({
            "formats": ["rdfs-turtle", "owl-rdfxml", "ossie-yaml", "owl-turtle", "linkml-yaml"],
            "licenses": ["MIT", "Apache-2.0"], "published_at": "2026-10-14T10:20:30.123456+02:30",
            "keywords": ["sensors", "observations"], "future": {"enabled": True},
        })
        result = _decode_search(json.dumps(data), 1, 2)
        self.assertEqual(result.hits[0].model_dump(mode="json"), {
            "base_url": "urn:a", "namespace": "example", "name": "sensors", "version": "1.2+fix",
            "title": "Sensors", "description": "Sensor observations",
            "formats": ["rdfs-turtle", "owl-rdfxml", "ossie-yaml", "owl-turtle", "linkml-yaml"],
            "licenses": ["MIT", "Apache-2.0"], "published_at": "2026-10-14T10:20:30.123456+02:30",
            "keywords": ["sensors", "observations"],
        })
        self.assertEqual(result.hits[0].published_at,
                         datetime(2026, 10, 14, 10, 20, 30, 123456, timezone(timedelta(hours=2, minutes=30))))
        self.assertNotIn("future", result.model_dump())

    def test_rejects_invalid_summary_values(self):
        values = [(field, " \t") for field in ("namespace", "name", "version", "title", "description")]
        values += [("base_url", "relative"), ("title", 1), ("formats", []),
                   ("formats", ["owl"]), ("formats", ["OWL-TURTLE"]), ("licenses", [" "]), ("keywords", [1]),
                   ("published_at", "2026-10-14T10:20:30"),
                   ("published_at", "2026-02-30T10:20:30Z"), ("published_at", 1791973230)]
        for field, value in values:
            with self.subTest(field=field, value=value):
                data = search_body(["urn:a"])
                data["hits"][0]["document"][field] = value
                with self.assertRaises(ValueError):
                    _decode_search(json.dumps(data), 1, 2)

    def test_preserves_absent_and_empty_facets(self):
        for fields in ({}, {"facet_counts": []},
                       {"facet_counts": [{"field_name": "licenses", "counts": []}]}):
            with self.subTest(fields=fields):
                data = search_body()
                data.update(fields)
                result = _decode_search(json.dumps(data), 1, 2)
                self.assertEqual(result.model_dump().get("facet_counts"), fields.get("facet_counts"))
                self.assertEqual("facet_counts" in result.model_dump(), "facet_counts" in fields)

    def test_preserves_facet_order_and_integer_counts_while_discarding_unknown_properties(self):
        data = search_body()
        data["facet_counts"] = [
            {"field_name": "namespace", "counts": [
                {"value": "z", "count": 8.0, "highlighted": "<mark>z</mark>"},
                {"value": "a", "count": 0},
            ], "stats": {"total_values": 2}},
            {"field_name": "formats", "counts": [{"value": "owl-turtle", "count": 18446744073709551617}]},
        ]
        result = _decode_search(json.dumps(data), 1, 2)
        self.assertEqual(result.model_dump()["facet_counts"], [
            {"field_name": "namespace", "counts": [{"value": "z", "count": 8}, {"value": "a", "count": 0}]},
            {"field_name": "formats", "counts": [{"value": "owl-turtle", "count": 18446744073709551617}]},
        ])
        self.assertIsInstance(result.facet_counts[0], FacetCount)
        self.assertIsInstance(result.facet_counts[0].counts[0], FacetValueCount)
        self.assertIs(type(result.facet_counts[0].counts[0].count), int)

    def test_rejects_invalid_facet_shapes_and_counts(self):
        facets = [None, {}, [None], [{}], [{"field_name": "formats"}],
                  [{"field_name": 1, "counts": []}], [{"field_name": "formats", "counts": None}],
                  [{"field_name": "formats", "counts": [{}]}],
                  [{"field_name": "formats", "counts": [{"value": "owl-turtle"}]}],
                  [{"field_name": "formats", "counts": [{"count": 1}]}],
                  [{"field_name": "formats", "counts": [{"value": 1, "count": 1}]}]]
        facets += [[{"field_name": "formats", "counts": [{"value": "owl-turtle", "count": count}]}]
                   for count in (-1, 0.5, "1", True)]
        for value in facets:
            with self.subTest(facet_counts=value):
                data = search_body()
                data["facet_counts"] = value
                with self.assertRaises(ValueError):
                    _decode_search(json.dumps(data), 1, 2)

    def test_rejects_malformed_hit_and_request_shapes(self):
        for field, value in (("hits", {}), ("hits", [{}]), ("hits", [{"document": []}]),
                             ("hits", [{"document": {}}]), ("request_params", [])):
            with self.subTest(field=field, value=value):
                data = search_body()
                data[field] = value
                with self.assertRaises(ValueError):
                    _decode_search(json.dumps(data), 1, 2)

    def test_rejects_grouped_response_even_with_hits(self):
        data = search_body()
        data["grouped_hits"] = []
        with self.assertRaises(ValueError):
            _decode_search(json.dumps(data), 1, 2)

    def test_rejects_wrong_consumed_field_types(self):
        for field, value in (("found", True), ("search_cutoff", "false"), ("found", 1.5)):
            with self.subTest(field=field, value=value):
                data = search_body()
                data[field] = value
                with self.assertRaises(ValueError):
                    _decode_search(json.dumps(data), 1, 2)

    def test_rejects_mismatched_effective_pagination(self):
        for page, size in ((2, 2), (1, 3)):
            with self.subTest(page=page, per_page=size):
                with self.assertRaises(ValueError):
                    _decode_search(json.dumps(search_body(page=page, per_page=size)), 1, 2)

    def test_rejects_more_hits_than_requested(self):
        with self.assertRaises(ValueError):
            _decode_search(json.dumps(search_body(["urn:a", "urn:b", "urn:c"])), 1, 2)

    def test_accepts_integral_numbers_and_preserves_unbounded_total(self):
        result = _decode_search(json.dumps(search_body(page=1.0, per_page=2.0,
                                                       total=18446744073709551617)), 1, 2)
        self.assertEqual((result.page, result.per_page, result.total),
                         (1, 2, 18446744073709551617))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.requests.append((self.path, self.headers.get("Accept-Encoding")))
        status, body, headers, stall = self.server.routes[urlsplit(self.path).path]
        self.send_response(status)
        self.send_header("Content-Length", str(len(body) + int(stall)))
        self.send_header("Cache-Control", "no-store")
        for key, value in headers.items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()
        if stall:
            self.server.release.wait(2)

    def log_message(self, *args):
        pass


class TrackingOpener:
    def __init__(self, opener, responses):
        self.opener = opener
        self.responses = responses

    def open(self, *args, **kwargs):
        try:
            response = self.opener.open(*args, **kwargs)
        except HTTPError as error:
            self.responses.append(error)
            raise
        self.responses.append(response)
        return response


class CatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.server.requests = []
        self.server.routes = {}
        self.server.release = threading.Event()
        self.responses = []

    def tearDown(self):
        self.server.release.set()
        for response in self.responses:
            self.assertTrue(response.closed)

    def route(self, path, body=b"", status=200, headers=None, stall=False):
        self.server.routes[path] = status, body, headers or {}, stall
        return self.base + path

    def client(self, config=None):
        client = CatalogClient(config)
        client._opener = TrackingOpener(client._opener, self.responses)
        return client

    def search_client(self):
        return self.client(CatalogConfig(search_url=self.base + "/search", query_by="name,description"))

    def assert_failure(self, action, code, operation, url, status=None):
        with self.assertRaises(CatalogException) as caught:
            action()
        error = caught.exception.error
        self.assertEqual((error.code.value, error.operation.value, error.url, error.status),
                         (code, operation, url, status))
        self.assertEqual(str(caught.exception), error.message)
        if status is None:
            self.assertNotIn("status", error.model_dump())
        return error

    def test_omitted_search_settings_use_production_catalog_and_preserve_timeout(self):
        client = CatalogClient(CatalogConfig(timeout_ms=275))
        response = BytesIO(json.dumps(search_body(per_page=10, total=0)).encode())
        response.status = 200
        response.headers = {}
        with patch.object(client._opener, "open", return_value=response) as opened:
            result = client.search(SearchRequest(q="*"))
        request = opened.call_args.args[0]
        self.assertEqual(urlsplit(request.full_url)._replace(query="").geturl(),
                         "https://api.ohi.dev/search/collections/packages/documents/search")
        self.assertEqual(parse_qs(urlsplit(request.full_url).query),
                         {"q": ["*"], "query_by": ["title,name,description,namespace"],
                          "page": ["1"], "per_page": ["10"]})
        self.assertEqual(opened.call_args.kwargs["timeout"], 0.275)
        opened.assert_called_once()
        self.assertEqual((result.hits, result.total, result.page, result.per_page),
                         ([], 0, 1, 10))

    def test_invalid_configuration_fails_without_a_request(self):
        for settings in ({"search_url": self.base}, {"query_by": "name"}, {"timeout_ms": 0},
                         {"timeout_ms": 2147483648},
                         {"search_url": self.base + "?q=x", "query_by": "name"},
                         {"search_url": self.base + "#x", "query_by": "name"}):
            with self.subTest(settings=settings):
                config = CatalogConfig.model_construct(**settings)
                self.assert_failure(lambda: CatalogClient(config), "INVALID_INPUT", "CONFIGURE",
                                    settings.get("search_url"))
                self.assertEqual(self.server.requests, [])

    def test_constructor_copies_configuration_and_search_uses_defaults(self):
        self.route("/search", json.dumps(search_body(per_page=10)).encode())
        config = CatalogConfig(search_url=self.base + "/search", query_by="name")
        client = self.client(config)
        config.search_url = self.base + "/changed"
        config.query_by = "changed"
        result = client.search(SearchRequest(q="*"))
        self.assertEqual((result.page, result.per_page), (1, 10))
        path, encoding = self.server.requests[0]
        self.assertEqual(urlsplit(path).path, "/search")
        self.assertEqual(parse_qs(urlsplit(path).query),
                         {"q": ["*"], "query_by": ["name"], "page": ["1"], "per_page": ["10"]})
        self.assertEqual(encoding, "identity")

    def test_invalid_search_input_fails_without_a_request(self):
        client = self.search_client()
        for settings in ({"q": " \t"}, {"q": "*", "page": 0},
                         {"q": "*", "per_page": 0}, {"q": "*", "per_page": 251},
                         {"q": "*", "facet_by": ""}, {"q": "*", "facet_by": " \t"},
                         {"q": "*", "facet_by": 1},
                         {"q": "*", "max_facet_values": 0}, {"q": "*", "max_facet_values": 1.5},
                         {"q": "*", "max_facet_values": "30"}, {"q": "*", "max_facet_values": True}):
            with self.subTest(settings=settings):
                self.assert_failure(lambda: client.search(SearchRequest.model_construct(**settings)),
                                    "INVALID_INPUT", "SEARCH", self.base + "/search")
                self.assertEqual(self.server.requests, [])

    def test_accepts_page_size_limits(self):
        for size in (1, 250):
            with self.subTest(size=size):
                self.route("/search", json.dumps(search_body(per_page=size)).encode())
                result = self.search_client().search(SearchRequest(q="*", per_page=size))
                self.assertEqual(result.per_page, size)
                self.assertEqual(parse_qs(urlsplit(self.server.requests[-1][0]).query)["per_page"], [str(size)])

    def test_search_forwards_integral_facet_limits_as_integers(self):
        self.route("/search", json.dumps(search_body(per_page=10)).encode())
        for value, expected in ((1, "1"), (30.0, "30")):
            with self.subTest(value=value):
                self.search_client().search(SearchRequest.model_construct(q="*", max_facet_values=value))
                self.assertEqual(parse_qs(urlsplit(self.server.requests[-1][0]).query)["max_facet_values"],
                                 [expected])

    def test_search_encodes_parameters_once_and_preserves_order_and_duplicates(self):
        data = search_body(["urn:b", "urn:a", "urn:b"], page=2, per_page=3)
        data["facet_counts"] = [{"field_name": "formats", "counts": [{"value": "owl-turtle", "count": 2}]}]
        self.route("/search", json.dumps(data).encode())
        result = self.search_client().search(SearchRequest(
            q="温度 + & sensors", page=2, per_page=3,
            filter_by="namespace:=[`a+b`,`c&d`]", sort_by="published_at:desc,name:asc",
            facet_by="namespace,formats(sort_by:_alpha:asc),licenses(a+b&c)",
            max_facet_values=30,
        ))
        self.assertEqual([hit.base_url for hit in result.hits], ["urn:b", "urn:a", "urn:b"])
        self.assertEqual(result.hits[0].title, "Sensors")
        self.assertEqual(result.facet_counts[0].model_dump(), {
            "field_name": "formats", "counts": [{"value": "owl-turtle", "count": 2}],
        })
        self.assertEqual(len(self.server.requests), 1)
        self.assertEqual(parse_qs(urlsplit(self.server.requests[0][0]).query), {
            "q": ["温度 + & sensors"], "query_by": ["name,description"], "page": ["2"],
            "per_page": ["3"], "filter_by": ["namespace:=[`a+b`,`c&d`]"],
            "sort_by": ["published_at:desc,name:asc"],
            "facet_by": ["namespace,formats(sort_by:_alpha:asc),licenses(a+b&c)"],
            "max_facet_values": ["30"],
        })

    def test_inspect_uses_exact_opaque_path_and_does_not_fetch_dependencies(self):
        url = self.route("/opaque", (EXAMPLES / "sensors-package.json").read_bytes()) + "?token=a%2Bb"
        result = self.client().inspect(url)
        self.assertEqual((result.name, result.title, result.publisher), ("sensors", "Sensors", "Example Project"))
        self.assertEqual([d.base_url for d in result.dependencies], ["https://example.org/packages/units/2.0.0/"])
        self.assertEqual(self.server.requests, [("/opaque?token=a%2Bb", "identity")])

    def test_get_returns_original_binary_bytes_without_sdk_caching(self):
        url = self.route("/file", b"\x00\xff\x80\n")
        client = self.client()
        self.assertEqual(client.get(url), b"\x00\xff\x80\n")
        self.assertEqual(client.get(url), b"\x00\xff\x80\n")
        self.assertEqual(self.server.requests, [("/file", "identity"), ("/file", "identity")])

    def test_get_accepts_empty_files(self):
        self.assertEqual(self.client().get(self.route("/empty")), b"")

    def test_rejects_unsupported_transport_urls_without_a_request(self):
        for url in ("urn:ohi:one", "/relative", "http://user:secret@localhost/a", "https:///a"):
            with self.subTest(url=url):
                self.assert_failure(lambda: self.client().get(url), "INVALID_INPUT", "GET", url)
                self.assertEqual(self.server.requests, [])

    def test_http_404_preserves_status(self):
        url = self.route("/missing", status=404)
        self.assert_failure(lambda: self.client().get(url), "HTTP_ERROR", "GET", url, 404)

    def test_http_503_preserves_nested_server_json_and_nulls(self):
        url = self.route("/unavailable", b'{"code":"FUTURE","details":{"x":[null,1]},'
                         b'"recovery":{"args":{"cursor":null}}}', status=503)
        error = self.assert_failure(lambda: self.client().get(url), "HTTP_ERROR", "GET", url, 503)
        self.assertEqual(json.loads(error.model_dump_json())["response_json"], {
            "code": "FUTURE", "details": {"x": [None, 1]}, "recovery": {"args": {"cursor": None}},
        })

    def test_nonobject_or_invalid_http_error_body_keeps_http_failure(self):
        for body in (b"not JSON", b"{", b"[]", b'{"message":"\xff"}'):
            with self.subTest(body=body):
                url = self.route("/error", body, status=503)
                error = self.assert_failure(lambda: self.client().get(url), "HTTP_ERROR", "GET", url, 503)
                self.assertNotIn("response_json", error.model_dump())

    def test_malformed_search_body_is_invalid_response(self):
        self.route("/search", b"{")
        self.assert_failure(lambda: self.search_client().search(SearchRequest(q="*")),
                            "INVALID_RESPONSE", "SEARCH", self.base + "/search", 200)

    def test_invalid_publication_is_invalid_response(self):
        url = self.route("/publication", b"{}")
        self.assert_failure(lambda: self.client().inspect(url), "INVALID_RESPONSE", "INSPECT", url, 200)

    def test_refused_connection_has_no_status(self):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            url = f"http://127.0.0.1:{sock.getsockname()[1]}/refused"
        self.assert_failure(lambda: self.client().get(url), "CONNECTION_ERROR", "GET", url)

    def test_redirect_does_not_request_target(self):
        url = self.route("/redirect", status=302, headers={"Location": self.base + "/target"})
        self.assert_failure(lambda: self.client().get(url), "HTTP_ERROR", "GET", url, 302)
        self.assertEqual(self.server.requests, [("/redirect", "identity")])

    def test_partial_content_is_an_http_error(self):
        url = self.route("/partial", b"partial", status=206)
        self.assert_failure(lambda: self.client().get(url), "HTTP_ERROR", "GET", url, 206)

    def test_rejects_unexpected_content_encoding(self):
        url = self.route("/compressed", b"coding", headers={"Content-Encoding": "gzip"})
        self.assert_failure(lambda: self.client().get(url), "INVALID_RESPONSE", "GET", url, 200)

    def test_stalled_success_body_times_out_without_returning_partial_bytes(self):
        url = self.route("/stall", b"partial", stall=True)
        client = self.client(CatalogConfig(timeout_ms=50))
        start = time.monotonic()
        self.assert_failure(lambda: client.get(url), "TIMEOUT", "GET", url, 200)
        self.assertLess(time.monotonic() - start, 1.5)

    def test_stalled_error_body_preserves_known_http_failure(self):
        url = self.route("/stall-error", b'{"partial":', status=503, stall=True)
        client = self.client(CatalogConfig(timeout_ms=50))
        error = self.assert_failure(lambda: client.get(url), "HTTP_ERROR", "GET", url, 503)
        self.assertNotIn("response_json", error.model_dump())
