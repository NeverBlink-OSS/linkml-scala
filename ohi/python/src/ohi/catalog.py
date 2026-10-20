"""Retrieve one catalog page, publication or file through native HTTP."""

import json
from http.client import HTTPException
from importlib.resources import files
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from jsonschema import Draft202012Validator, FormatError, ValidationError

from .generated import Publication
from .generated_config import CatalogConfig
from .generated_error import CatalogError
from .generated_search import SearchResult
from .generated_search_request import SearchRequest
from .publication import decode_publication


_validators = {
    name: Draft202012Validator(
        json.loads(files(__package__).joinpath(f"{name}.schema.json").read_text(encoding="utf-8")),
        format_checker=Draft202012Validator.FORMAT_CHECKER,
    )
    for name in ("catalog-config", "search-request", "search")
}


class CatalogException(Exception):
    """Catalog failure with a generated, serializable error record."""

    def __init__(self, error: CatalogError):
        self.error = error
        super().__init__(error.message)


def _failure(code, operation, message, url: str | None = None, status=None, response_json=None):
    return CatalogException(CatalogError(
        code=code, operation=operation, message=message,
        url=url, status=status, response_json=response_json,
    ))


def _validate_url(url: str, operation, search=False):
    try:
        Draft202012Validator.FORMAT_CHECKER.check(url, "uri")
        parsed = urlsplit(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError("URL must be absolute HTTP(S) with a host")
        if parsed.username is not None or parsed.password is not None:
            raise ValueError("URL must not contain credentials")
        parsed.port
        if search and ("?" in url or "#" in url):
            raise ValueError("search_url must not contain a query or fragment")
    except (ValueError, FormatError) as error:
        raise _failure("INVALID_INPUT", operation, str(error), url) from error


def _decode_search(text, page, per_page):
    data = json.loads(text)
    try:
        if not isinstance(data, dict) or "grouped_hits" in data:
            raise ValueError("search response must be an ungrouped object")
        if not isinstance(data["hits"], list):
            raise ValueError("search response requires a hits array")
        hits = [hit["document"] for hit in data["hits"]]
        result = {
            "hits": hits, "total": data["found"], "page": data["page"],
            "per_page": data["request_params"]["per_page"],
            "search_cutoff": data["search_cutoff"],
        }
        if "facet_counts" in data:
            result["facet_counts"] = data["facet_counts"]
        _validators["search"].validate(result)
    except (KeyError, TypeError, ValidationError) as error:
        raise ValueError(f"invalid search response: {error}") from error
    if result["page"] != page or result["per_page"] != per_page:
        raise ValueError("response pagination differs from the requested page")
    if len(hits) > per_page:
        raise ValueError("response contains more hits than per_page")
    # JSON Schema accepts integers such as 8.0, while generated fields use StrictInt.
    for field in ("total", "page", "per_page"):
        result[field] = int(result[field])
    for facet in result.get("facet_counts", []):
        for value in facet["counts"]:
            value["count"] = int(value["count"])
    return SearchResult.model_validate(result, extra="ignore")


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class CatalogClient:
    """Synchronous HTTP operations. Omitting both search settings selects ohi.dev."""

    def __init__(self, config: CatalogConfig | None = None):
        config = CatalogConfig() if config is None else config
        url = config.search_url
        try:
            data = config.model_dump(warnings=False)
            _validators["catalog-config"].validate(data)
            if ("search_url" in data) != ("query_by" in data):
                raise ValueError("search_url and query_by must be supplied together")
        except (ValueError, ValidationError) as error:
            raise _failure("INVALID_INPUT", "CONFIGURE", str(error), url) from error
        if url is not None:
            _validate_url(url, "CONFIGURE", search=True)
        self._search_url = (url if url is not None else
                            "https://api.ohi.dev/search/collections/packages/documents/search")
        self._query_by = data.get("query_by", "title,name,description,namespace")
        self._timeout = data.get("timeout_ms", 30000) / 1000
        self._opener = build_opener(_NoRedirect())

    def search(self, request: SearchRequest) -> SearchResult:
        url = self._search_url
        try:
            params = request.model_dump(warnings=False)
            _validators["search-request"].validate(params)
        except (ValueError, ValidationError) as error:
            raise _failure("INVALID_INPUT", "SEARCH", str(error), url) from error
        params["page"] = int(params.get("page", 1))
        params["per_page"] = int(params.get("per_page", 10))
        if "max_facet_values" in params:
            params["max_facet_values"] = int(params["max_facet_values"])
        params["query_by"] = self._query_by
        body = self._get(url + "?" + urlencode(params), "SEARCH", url)
        try:
            return _decode_search(body.decode("utf-8"), params["page"], params["per_page"])
        except ValueError as error:
            raise _failure("INVALID_RESPONSE", "SEARCH", str(error), url, 200) from error

    def inspect(self, base_url: str) -> Publication:
        _validate_url(base_url, "INSPECT")
        body = self._get(base_url, "INSPECT", base_url)
        try:
            return decode_publication(body.decode("utf-8"))
        except ValueError as error:
            raise _failure("INVALID_RESPONSE", "INSPECT", str(error), base_url, 200) from error

    def get(self, url: str) -> bytes:
        _validate_url(url, "GET")
        return self._get(url, "GET", url)

    def _get(self, url, operation, error_url):
        status = None
        try:
            try:
                response = self._opener.open(
                    Request(url, headers={"Accept-Encoding": "identity"}), timeout=self._timeout,
                )
            except HTTPError as error:
                response = error
            with response:
                status = response.status
                if status != 200:
                    response_json = None
                    try:
                        value = json.loads(response.read().decode("utf-8"))
                        if isinstance(value, dict):
                            response_json = value
                    except (OSError, HTTPException, ValueError):
                        pass  # The known HTTP status takes precedence over body failures.
                    raise _failure("HTTP_ERROR", operation, f"HTTP status {status}",
                                   error_url, status, response_json)
                body = response.read()
                if response.headers.get("Content-Encoding", "identity").strip().lower() != "identity":
                    raise _failure("INVALID_RESPONSE", operation, "unsupported HTTP content encoding",
                                   error_url, status)
                return body
        except (OSError, HTTPException) as error:
            reason = error.reason if isinstance(error, URLError) else error
            code = "TIMEOUT" if isinstance(reason, TimeoutError) else "CONNECTION_ERROR"
            raise _failure(code, operation, str(error), error_url, status) from error
