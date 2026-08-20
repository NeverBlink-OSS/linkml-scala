"""Tests for the LinkML-Scala Python bindings.

Run them with ``./mill nativelib.pythonTest``, which rebuilds the shared library first. To run them
against a library you already have, ``python -m unittest discover -s python``.

``./mill nativelib.pythonWheelTest`` runs this same file against an installed wheel, from a copy in
a temporary directory so that the sources here cannot shadow the package under test. That is what
``LINKML_SCALA_REPO`` is for: from there, the fixtures are no longer two directories up.

These check the binding, not the generators: that every call reaches the library, comes back with
plausible output, and that failures surface as exceptions rather than crashes. The generators
themselves are covered by the Scala test suite.
"""

from __future__ import annotations

import json
import os
import textwrap
import threading
import unittest
from pathlib import Path

import linkml_scala
from linkml_scala import LinkMlError, SchemaLoadError

REPO_ROOT = Path(os.environ.get("LINKML_SCALA_REPO") or Path(__file__).resolve().parents[1])


def schema(name: str, body: str) -> str:
    """A minimal schema wrapping ``body``.

    Every schema needs ``linkml:types`` imported before `string` and friends resolve, and the
    bindings resolve that one internally rather than off disk.
    """
    return textwrap.dedent(
        f"""
        id: https://example.org/{name}
        name: {name}
        imports:
          - linkml:types
        default_range: string
        {textwrap.indent(textwrap.dedent(body).strip(), "        ").lstrip()}
        """
    ).strip()


PERSON = schema(
    "person",
    """
    classes:
      Person:
        tree_root: true
        attributes:
          name:
            range: string
            required: true
          pet:
            range: Animal
      Animal:
        attributes:
          species:
            range: string
    """,
)

# `range: NoSuchThing` names nothing, which is fatal: the schema cannot be loaded at all.
BROKEN = schema(
    "broken",
    """
    classes:
      Thing:
        attributes:
          x:
            range: NoSuchThing
    """,
)

FIXED = BROKEN.replace("NoSuchThing", "string")


class LoadTest(unittest.TestCase):
    def test_load_string(self):
        with linkml_scala.load_string(PERSON) as loaded:
            self.assertEqual([], loaded.issues(linkml_scala.FATAL))
            self.assertIn("Person", loaded.json_schema())

    def test_load_file(self):
        model = REPO_ROOT / "tests" / "resources" / "models" / "basic" / "model.yaml"
        with linkml_scala.load_file(model) as loaded:
            self.assertIn("SomeClass", loaded.json_schema())
            # Loading by path records the path as the report's run id.
            self.assertEqual(str(model), loaded.report["validation_run_id"])

    def test_load_string_resolves_imports_from_the_map(self):
        root = schema(
            "root",
            """
            imports:
              - linkml:types
              - shapes
            classes:
              Drawing:
                attributes:
                  shape:
                    range: Square
            """,
        )
        shapes = schema(
            "shapes",
            """
            classes:
              Square:
                attributes:
                  side:
                    range: string
            """,
        )
        with linkml_scala.load_string(root, {"shapes.yaml": shapes}) as loaded:
            generated = loaded.json_schema()
            self.assertIn("Drawing", generated)
            self.assertIn("Square", generated)

    def test_load_path_survives_an_import_cycle_through_the_root(self):
        # `load_string` would load the root a second time here, because the root it was handed has no
        # path to match `- root` against. `load_path` reads the root through the map, so it does.
        root = schema(
            "root",
            """
            imports:
              - linkml:types
              - other
            classes:
              Drawing:
                attributes:
                  shape:
                    range: Square
            """,
        )
        other = schema(
            "other",
            """
            imports:
              - linkml:types
              - root
            classes:
              Square:
                attributes:
                  side:
                    range: string
            """,
        )
        imports = {"root.yaml": root, "other.yaml": other}
        with linkml_scala.load_path("root.yaml", imports) as loaded:
            self.assertEqual([], loaded.issues(linkml_scala.FATAL))
            self.assertIn("Drawing", loaded.json_schema())

    def test_a_missing_import_is_reported(self):
        root = schema(
            "root",
            """
            imports:
              - linkml:types
              - nowhere
            classes:
              Thing:
                attributes:
                  name:
                    range: string
            """,
        )
        with self.assertRaises(SchemaLoadError) as caught:
            linkml_scala.load_string(root, {})
        self.assertIn("nowhere", json.dumps(caught.exception.report))

    def test_fatal_problems_raise_with_a_report(self):
        with self.assertRaises(SchemaLoadError) as caught:
            linkml_scala.load_string(BROKEN)
        issues = caught.exception.report["issues"]
        self.assertEqual([linkml_scala.FATAL], sorted({issue["severity"] for issue in issues}))
        self.assertIn("NoSuchThing", json.dumps(issues))

    def test_unparseable_yaml_raises(self):
        with self.assertRaises(SchemaLoadError):
            linkml_scala.load_string("this: is: not: a: schema:")

    def test_load_needs_a_schema_or_a_path(self):
        with self.assertRaises(LinkMlError):
            linkml_scala.runtime().load_string(None, None)


class GeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = linkml_scala.load_string(PERSON)

    @classmethod
    def tearDownClass(cls):
        cls.schema.close()

    def test_json_schema(self):
        generated = json.loads(self.schema.json_schema())
        self.assertEqual("#/$defs/Person", generated["$ref"])
        self.assertIn("Animal", generated["$defs"])

    def test_json_schema_open_allows_extra_properties(self):
        closed = json.loads(self.schema.json_schema())["$defs"]["Person"]
        opened = json.loads(self.schema.json_schema(open=True))["$defs"]["Person"]
        self.assertFalse(closed["additionalProperties"])
        self.assertTrue(opened["additionalProperties"])

    def test_json_schema_tree_root_override(self):
        generated = json.loads(self.schema.json_schema(tree_root="Animal"))
        self.assertEqual("#/$defs/Animal", generated["$ref"])

    def test_shacl(self):
        generated = self.schema.shacl()
        self.assertIn("http://www.w3.org/ns/shacl#NodeShape", generated)
        self.assertIn("https://example.org/person/Person", generated)
        # N-Triples: one statement per line, each ending in a dot.
        for line in generated.splitlines():
            self.assertTrue(line.endswith("."), line)

    def test_shacl_open_drops_the_closed_constraint(self):
        self.assertIn("shacl#closed", self.schema.shacl())
        self.assertNotIn('shacl#closed> "true"', self.schema.shacl(open=True))

    def test_rdfs(self):
        self.assertIn("rdf-schema#Class", self.schema.rdfs())

    def test_linkml(self):
        # Deriving pushes inherited and referenced slots down into each class' attributes.
        self.assertIn("attributes:", self.schema.linkml())
        self.assertIn('"attributes"', self.schema.linkml(output_format="json"))

    def test_linkml_pruning_drops_unreachable_classes(self):
        unreachable = schema(
            "unreachable",
            """
            classes:
              Root:
                tree_root: true
                attributes:
                  name:
                    range: string
              Orphan:
                attributes:
                  name:
                    range: string
            """,
        )
        with linkml_scala.load_string(unreachable) as loaded:
            self.assertIn("Orphan", loaded.linkml(pruning_mode="skip"))
            self.assertNotIn("Orphan", loaded.linkml(pruning_mode="treeRoot"))

    def test_table_schema(self):
        self.assertIn("fields", json.loads(self.schema.table_schema()))

    def test_graphql(self):
        self.assertIn("type Person", self.schema.graphql())

    def test_er_diagram(self):
        generated = self.schema.er_diagram()
        self.assertIn("erDiagram", generated)
        # Type-ranged slots become attributes; optional ones get a trailing '?' on their type.
        self.assertIn("string? species", generated)
        self.assertNotIn("string? species", self.schema.er_diagram(optional_marker=False))
        # Class-ranged slots become relationship lines instead.
        self.assertIn('Person ||--o| Animal : "pet"', generated)

    def test_scala(self):
        files = self.schema.scala(package="com.example.model")
        self.assertIn("Person.scala", files)
        self.assertIn("package com.example.model", files["Person.scala"])

    def test_an_unexported_function_cannot_be_called(self):
        # Each generator is its own exported symbol now, so a name that does not exist fails at the
        # ctypes layer rather than being dispatched and rejected by the library.
        with self.assertRaises(AttributeError):
            linkml_scala.runtime().document("linkml_cuneiform", self.schema._handle)

    def test_unknown_pruning_mode(self):
        with self.assertRaises(LinkMlError) as caught:
            self.schema.linkml(pruning_mode="vigorously")
        self.assertIn("vigorously", str(caught.exception))

    def test_an_unknown_option_is_rejected_rather_than_ignored(self):
        with self.assertRaises(LinkMlError) as caught:
            linkml_scala.runtime().document(
                "linkml_json_schema", self.schema._handle, {"opne": True}
            )
        self.assertIn("opne", str(caught.exception))


class LintTest(unittest.TestCase):
    def test_a_clean_schema_has_no_issues(self):
        with linkml_scala.load_string(PERSON) as loaded:
            self.assertEqual([], loaded.lint()["issues"])

    def test_warnings_do_not_stop_a_schema_from_loading(self):
        # No class is marked `tree_root`, which is a warning rather than an error.
        rootless = schema(
            "rootless",
            """
            classes:
              Thing:
                attributes:
                  name:
                    range: string
            """,
        )
        with linkml_scala.load_string(rootless) as loaded:
            warnings = loaded.issues(linkml_scala.WARNING)
            self.assertTrue(warnings, "expected a warning about the missing tree root")
            self.assertTrue(all("message" in warning for warning in warnings))
            # Loading lints as it goes, so the report is already there.
            self.assertEqual(loaded.report, loaded.lint())

    def test_infer_messages_off_leaves_messages_out(self):
        rootless = schema(
            "rootless",
            """
            classes:
              Thing:
                attributes:
                  name:
                    range: string
            """,
        )
        with linkml_scala.load_string(rootless, infer_messages=False) as loaded:
            self.assertTrue(loaded.report["issues"])
            self.assertFalse(any("message" in issue for issue in loaded.report["issues"]))


class HandleTest(unittest.TestCase):
    def test_using_a_closed_schema_raises(self):
        loaded = linkml_scala.load_string(PERSON)
        loaded.close()
        with self.assertRaises(LinkMlError):
            loaded.json_schema()

    def test_closing_twice_is_fine(self):
        loaded = linkml_scala.load_string(PERSON)
        loaded.close()
        loaded.close()

    def test_an_unknown_handle_raises(self):
        with self.assertRaises(LinkMlError) as caught:
            linkml_scala.runtime().document("linkml_lint", 999_999)
        self.assertIn("999999", str(caught.exception))

    def test_handle_zero_raises(self):
        # 0 is what loading returns when it refused, so it must never be usable.
        with self.assertRaises(LinkMlError):
            linkml_scala.runtime().document("linkml_lint", 0)

    def test_handles_are_independent(self):
        with linkml_scala.load_string(PERSON) as first:
            with linkml_scala.load_string(FIXED) as second:
                self.assertIn("Person", first.json_schema())
                self.assertIn("Thing", second.json_schema())


class RuntimeTest(unittest.TestCase):
    def test_the_runtime_is_shared(self):
        self.assertIs(linkml_scala.runtime(), linkml_scala.runtime())

    def test_library_path_points_at_a_real_file(self):
        self.assertTrue(linkml_scala.library_path().is_file())

    def test_other_threads_can_call_in(self):
        # Every thread has to attach to the isolate before its first call. Getting that wrong takes
        # the whole process down rather than raising, so this test either passes or nothing does.
        results: list[str] = []
        errors: list[BaseException] = []

        def work():
            try:
                with linkml_scala.load_string(PERSON) as loaded:
                    results.append(loaded.json_schema())
            except BaseException as error:  # noqa: BLE001 - reported on the main thread
                errors.append(error)

        threads = [threading.Thread(target=work) for _ in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual([], errors)
        self.assertEqual(4, len(results))
        self.assertEqual(1, len(set(results)), "the same schema should generate the same output")

    def test_non_ascii_survives_the_round_trip(self):
        # The response travels as UTF-8 inside JSON, through C, and back into Python.
        unicode_schema = schema(
            "unicode",
            """
            classes:
              Terrarium:
                tree_root: true
                attributes:
                  wąż:
                    description: "grzegorz brzęczyszczykiewicz 🐍"
                    range: string
            """,
        )
        with linkml_scala.load_string(unicode_schema) as loaded:
            generated = loaded.linkml()
            self.assertIn("wąż", generated)
            self.assertIn("🐍", generated)

    def test_a_large_output_comes_back_whole(self):
        # Responses are copied into unmanaged memory as UTF-8 and read back through a pointer, so a
        # multi-megabyte one is worth exercising: a truncated copy would look like valid output.
        count = 1000
        classes = "\n".join(
            f"  Class{index}:\n"
            f"    attributes:\n"
            f"      a{index}:\n"
            f"        range: string\n"
            f"      b{index}:\n"
            f"        range: Class{(index + 1) % count}"
            for index in range(count)
        )
        with linkml_scala.load_string(schema("big", f"classes:\n{classes}")) as loaded:
            generated = loaded.shacl()
        self.assertGreater(len(generated), 1_000_000)
        # Every line is a complete statement, so nothing was cut short mid-copy.
        self.assertTrue(all(line.endswith(".") for line in generated.splitlines()))


if __name__ == "__main__":
    unittest.main()
