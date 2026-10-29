import json
import unittest
from datetime import datetime, timezone
from pathlib import Path

from ohi.generated import Publication
from ohi.publication import decode_publication


EXAMPLES = Path(__file__).resolve().parents[2] / "examples"


def example(name="sensors"):
    return json.loads((EXAMPLES / f"{name}-package.json").read_text(encoding="utf-8"))


def decode(value):
    return decode_publication(json.dumps(value))


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.data = example()

    def reject(self):
        with self.assertRaises(ValueError):
            decode(self.data)

    def test_maps_publication_fields(self):
        publication = decode(self.data)
        self.assertIsInstance(publication, Publication)
        self.assertEqual(publication.schema_version, 2)
        self.assertEqual(publication.base_url, "https://example.org/packages/sensors/1.0.0/")
        self.assertEqual(publication.version, "1.0.0")
        self.assertEqual(publication.namespace, "example")
        self.assertEqual(publication.name, "sensors")
        self.assertEqual(publication.title, "Sensors")
        self.assertEqual(publication.published_at, datetime(2026, 10, 13, 12, 30, tzinfo=timezone.utc))
        self.assertEqual(publication.publisher, "Example Project")
        self.assertEqual(publication.licenses, ["Apache-2.0"])
        self.assertEqual(publication.namespace_iri, "https://example.org/sensors#")
        self.assertEqual(publication.preferred_prefix, "sensors")
        self.assertEqual([a.size_bytes for a in publication.artifacts], [42, 42])
        self.assertEqual([a.sha256 for a in publication.artifacts], ["0123456789abcdef" * 4] * 2)
        self.assertEqual(publication.description, "Sensors and their observations.")
        self.assertEqual(publication.keywords, ["sensors", "observations"])
        self.assertEqual(
            [(a.format.value, a.origin.value, a.url, a.roots) for a in publication.artifacts],
            [
                ("owl-turtle", "UPSTREAM", "https://example.org/packages/sensors/1.0.0/sensors.ttl",
                 ["https://example.org/packages/sensors/1.0.0/sensors.ttl"]),
                ("linkml-yaml", "GENERATED", "https://example.org/packages/sensors/1.0.0/sensors.yaml",
                 ["https://example.org/packages/sensors/1.0.0/sensors.yaml"]),
            ],
        )
        self.assertEqual([d.base_url for d in publication.dependencies],
                         ["https://example.org/packages/units/2.0.0/"])

    def test_accepts_no_dependencies(self):
        publication = decode(example("units"))
        self.assertEqual(publication.base_url, "https://example.org/packages/units/2.0.0/")
        self.assertEqual(publication.artifacts[0].url,
                         "https://example.org/packages/units/2.0.0/units.ttl")
        self.assertEqual(publication.dependencies, [])
        self.assertIsNone(publication.namespace_iri)
        self.assertIsNone(publication.preferred_prefix)
        self.assertNotIn("namespace_iri", publication.model_dump())
        self.assertNotIn("preferred_prefix", publication.model_dump())

    def test_accepts_empty_keywords(self):
        self.assertEqual(decode(example("units")).keywords, [])

    def test_requires_new_publication_fields(self):
        for field in ("namespace", "keywords", "dependencies", "title", "published_at", "publisher", "licenses"):
            with self.subTest(field=field):
                self.data = example()
                del self.data[field]
                self.reject()

    def test_requires_artifact_roots_despite_publication_roots(self):
        self.data["roots"] = self.data["artifacts"][0].pop("roots")
        self.reject()

    def test_requires_dependency_base_despite_file_metadata(self):
        self.data["dependencies"][0] = {"url": "https://example.org/units.ttl"}
        self.reject()

    def test_rejects_nonstring_namespace(self):
        for value in (None, 7):
            with self.subTest(value=value):
                self.data["namespace"] = value
                self.reject()

    def test_rejects_scalar_keywords(self):
        self.data["keywords"] = "sensors"
        self.reject()

    def test_rejects_nonobject_publication(self):
        self.data = []
        self.reject()

    def test_rejects_nonobject_artifact(self):
        self.data["artifacts"][0] = None
        self.reject()

    def test_rejects_nonobject_dependency(self):
        self.data["dependencies"][0] = "units"
        self.reject()

    def test_rejects_empty_artifacts(self):
        self.data["artifacts"] = []
        self.reject()

    def test_rejects_empty_artifact_roots(self):
        self.data["artifacts"][0]["roots"] = []
        self.reject()

    def test_rejects_blank_namespace(self):
        self.data["namespace"] = " \t\n"
        self.reject()

    def test_preserves_opaque_version(self):
        self.data["version"] = " release + amendment "
        self.assertEqual(decode(self.data).version, " release + amendment ")

    def test_rejects_unsupported_revision(self):
        for value in (1, 3):
            with self.subTest(value=value):
                self.data["schema_version"] = value
                self.reject()

    def test_requires_artifact_size_and_digest(self):
        for field in ("size_bytes", "sha256"):
            with self.subTest(field=field):
                self.data = example()
                del self.data["artifacts"][0][field]
                self.reject()

    def test_rejects_prefix_without_namespace_iri(self):
        del self.data["namespace_iri"]
        self.reject()

    def test_rejects_malformed_datetime(self):
        self.data["published_at"] = "2026-02-30T12:30:00Z"
        self.reject()

    def test_serializes_timestamp_at_python_microsecond_precision(self):
        self.data["published_at"] = "2026-10-13T12:30:00.123456789+00:00"
        publication = decode(self.data)
        self.assertEqual(publication.published_at.microsecond, 123456)
        self.assertEqual(json.loads(publication.model_dump_json())["published_at"],
                         "2026-10-13T12:30:00.123456Z")

    def test_accepts_unbounded_integer_sizes(self):
        self.data["artifacts"][1]["size_bytes"] = 18446744073709551617
        publication = decode(self.data)
        self.assertEqual(publication.artifacts[1].size_bytes, 18446744073709551617)

    def test_rejects_invalid_artifact_sizes(self):
        for value in (-1, 42.0, 1.5, "42", True):
            with self.subTest(value=value):
                self.data["artifacts"][0]["size_bytes"] = value
                self.reject()

    def test_rejects_invalid_artifact_digest(self):
        self.data["artifacts"][0]["sha256"] = "a" * 63
        self.reject()

    def test_rejects_nonnumeric_revision(self):
        for value in ("1", True):
            with self.subTest(value=value):
                self.data["schema_version"] = value
                self.reject()

    def test_rejects_unknown_format(self):
        self.data["artifacts"][0]["format"] = "future-format"
        self.reject()

    def test_rejects_unknown_origin(self):
        self.data["artifacts"][0]["origin"] = "FUTURE"
        self.reject()

    def test_discards_extras_at_each_record_level(self):
        self.data["package"] = "obsolete"
        self.data["roots"] = ["obsolete"]
        self.data["artifacts"][0]["dependencies"] = "obsolete"
        self.data["dependencies"][0]["url"] = "obsolete"
        publication = decode(self.data)
        self.assertFalse(publication.model_extra)
        self.assertFalse(publication.artifacts[0].model_extra)
        self.assertFalse(publication.dependencies[0].model_extra)
        self.assertEqual(publication.namespace, "example")
        self.assertEqual(publication.artifacts[0].url,
                         "https://example.org/packages/sensors/1.0.0/sensors.ttl")
        self.assertEqual(publication.dependencies[0].base_url,
                         "https://example.org/packages/units/2.0.0/")

    def test_rejects_invalid_dependency_beside_extras(self):
        self.data["backend"] = True
        self.data["dependencies"][0]["base_url"] = 1
        with self.assertRaises(ValueError) as caught:
            decode(self.data)
        self.assertIn("base_url", str(caught.exception))

    def test_accepts_http_development_references(self):
        text = json.dumps(self.data).replace("https://example.org", "http://localhost:8080")
        publication = decode_publication(text)
        self.assertEqual(publication.base_url, "http://localhost:8080/packages/sensors/1.0.0/")
        self.assertEqual(publication.artifacts[0].url,
                         "http://localhost:8080/packages/sensors/1.0.0/sensors.ttl")
        self.assertEqual(publication.artifacts[0].roots,
                         ["http://localhost:8080/packages/sensors/1.0.0/sensors.ttl"])
        self.assertEqual(publication.dependencies[0].base_url,
                         "http://localhost:8080/packages/units/2.0.0/")

    def test_accepts_native_uri_schemes(self):
        self.data["base_url"] = "urn:ohi:sensors:1"
        self.assertEqual(decode(self.data).base_url, "urn:ohi:sensors:1")

    def test_preserves_uri_spelling(self):
        self.data["base_url"] = "https://Example.org/publication?revision=1#entry"
        self.assertEqual(decode(self.data).base_url,
                         "https://Example.org/publication?revision=1#entry")

    def test_rejects_duplicate_artifact_urls(self):
        self.data["artifacts"][1]["url"] = self.data["artifacts"][0]["url"]
        self.reject()

    def test_accepts_repeated_artifact_formats(self):
        self.data["artifacts"][1]["format"] = "owl-turtle"
        self.data["artifacts"].reverse()
        self.assertEqual([a.url for a in decode(self.data).artifacts], [
            "https://example.org/packages/sensors/1.0.0/sensors.yaml",
            "https://example.org/packages/sensors/1.0.0/sensors.ttl",
        ])

    def test_rejects_duplicate_dependency_bases(self):
        self.data["dependencies"].append({
            "base_url": "https://example.org/packages/units/2.0.0/", "url": "ignored",
        })
        self.reject()

    def test_rejects_self_dependency(self):
        self.data["dependencies"][0]["base_url"] = "https://example.org/packages/sensors/1.0.0/"
        self.reject()

    def test_preserves_dependency_identity_and_order(self):
        self.data["dependencies"] = [
            {"base_url": "https://example.org/units"}, {"base_url": "https://Example.org/units"},
        ]
        self.assertEqual([d.base_url for d in decode(self.data).dependencies],
                         ["https://example.org/units", "https://Example.org/units"])

    def test_preserves_artifact_roots_exactly(self):
        self.data["artifacts"][0]["roots"] = [
            "https://example.org/b.ttl", "https://example.org/a.ttl", "https://example.org/b.ttl",
        ]
        self.assertEqual(decode(self.data).artifacts[0].roots, [
            "https://example.org/b.ttl", "https://example.org/a.ttl", "https://example.org/b.ttl",
        ])

    def test_rejects_malformed_json(self):
        with self.assertRaises(ValueError):
            decode_publication("{")
