"""Tests for the ohi LinkML Python bindings.

Run them with ``./mill nativelib.native.pythonTest``, which rebuilds the shared library first. To run them
against a library you already have, ``python -m unittest discover -s python``.

``./mill nativelib.native.pythonWheelTest`` runs this same file against an installed wheel, from a copy in
a temporary directory so that the sources here cannot shadow the package under test. That is what
``LINKML_SCALA_REPO`` is for: from there, the fixtures are no longer two directories up.

These check the binding, not the generators: that every call reaches the library, comes back with
plausible output, and that failures surface as exceptions rather than crashes. The generators
themselves are covered by the Scala test suite.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import textwrap
import threading
import unittest
from pathlib import Path

import ohi_linkml
from ohi_linkml import LinkMlError, SchemaConversionError, SchemaLoadError

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


class ExportsTest(unittest.TestCase):
    def test_runtime_exports_every_public_name_it_defines(self):
        from ohi_linkml import _runtime

        defined = {
            name
            for name, value in vars(_runtime).items()
            if not name.startswith("_") and getattr(value, "__module__", None) == _runtime.__name__
        }
        self.assertEqual(defined - set(_runtime.__all__), set())


class FromOwlTest(unittest.TestCase):
    ONTOLOGY = textwrap.dedent(
        """
        <https://example.org/Person> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .
        <https://example.org/name> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#DatatypeProperty> .
        <https://example.org/name> <http://www.w3.org/2000/01/rdf-schema#domain> <https://example.org/Person> .
        <https://example.org/name> <http://www.w3.org/2000/01/rdf-schema#range> <http://www.w3.org/2001/XMLSchema#string> .
        <https://example.org/name> <http://www.w3.org/2002/07/owl#propertyChainAxiom> _:list .
        """
    ).strip()

    def test_reads_an_ontology_into_a_schema(self):
        schema = ohi_linkml.from_owl(self.ONTOLOGY)["schema"]
        self.assertIn("Person:", schema)
        self.assertIn("range: string", schema)

    def test_reads_turtle(self):
        turtle = "@prefix ex: <https://example.org/> .\nex:Book a <http://www.w3.org/2002/07/owl#Class> .\n"
        self.assertIn("Book:", ohi_linkml.from_owl(turtle)["schema"])

    def test_reads_n_triples_with_its_own_parser_when_asked(self):
        self.assertIn("Person:", ohi_linkml.from_owl(self.ONTOLOGY, input_format="nt")["schema"])

    def test_returns_what_it_left_out(self):
        result = ohi_linkml.from_owl(self.ONTOLOGY)
        self.assertTrue(result["warnings"])
        self.assertNotIn("# Not imported:", result["schema"])

    def test_uses_the_terms_of_a_mapped_import(self):
        ontology = self.ONTOLOGY + textwrap.dedent(
            """
            <https://example.org/onto> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Ontology> .
            <https://example.org/onto> <http://www.w3.org/2002/07/owl#imports> <https://other.example.com/onto> .
            <https://example.org/Person> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <https://other.example.com/Agent> .
            """
        )
        imported = textwrap.dedent(
            """
            id: https://other.example.com/schema
            name: other
            prefixes:
              other: https://other.example.com/
            default_prefix: other
            classes:
              Agent:
            """
        )
        config = {"imports": {"https://other.example.com/onto": "other.yaml"}}
        schema = ohi_linkml.from_owl(ontology, config=config, schemas={"other.yaml": imported})["schema"]
        self.assertIn("is_a: Agent", schema)
        self.assertIn("- other.yaml", schema)
        self.assertNotIn("Agent:", schema)

    def test_follows_the_config(self):
        config = {"default_prefix": "mine", "prefixes": {"mine": "https://example.org/"}}
        schema = ohi_linkml.from_owl(self.ONTOLOGY, config=config)["schema"]
        self.assertIn("default_prefix: mine", schema)

    def test_writes_json_when_asked(self):
        schema = json.loads(ohi_linkml.from_owl(self.ONTOLOGY, format="json")["schema"])
        self.assertIn("Person", schema["classes"])

    def test_unreadable_config_is_rejected(self):
        with self.assertRaises(LinkMlError) as raised:
            ohi_linkml.from_owl(self.ONTOLOGY, config={"naming": {"slots": "kebab"}})
        self.assertIn("kebab", str(raised.exception))


class FromOssieTest(unittest.TestCase):
    ONTOLOGY = textwrap.dedent(
        """
        version: 0.2.0.dev0
        name: flights
        ontology:
          - concept: Airport
            type: EntityType
            identify_by: [code]
            relationships:
              - name: code
                roles: [{concept: String}]
                multiplicity: OneToOne
                verbalizes: ['{Airport} is identified by {String}']
        """
    ).strip()

    def test_reads_an_ontology_into_a_schema(self):
        result = ohi_linkml.from_ossie(self.ONTOLOGY)
        self.assertEqual([], result["warnings"])
        schema = result["schema"]
        self.assertIn("name: flights", schema)
        self.assertIn("Airport:", schema)
        self.assertIn("identifier: true", schema)

    def test_writes_json_when_asked(self):
        schema = json.loads(ohi_linkml.from_ossie(self.ONTOLOGY, format="json")["schema"])
        self.assertEqual("flights", schema["name"])

    def test_uses_the_schema_id_it_was_given(self):
        schema = ohi_linkml.from_ossie(self.ONTOLOGY, schema_id="https://example.com/mine")["schema"]
        self.assertIn("id: https://example.com/mine", schema)

    # The point of the round trip: what comes out is a schema the library can load again.
    def test_result_loads_as_a_schema(self):
        with ohi_linkml.load_string(ohi_linkml.from_ossie(self.ONTOLOGY)["schema"]) as schema:
            self.assertIn("concept: Airport", schema.ossie())

    def test_unreadable_document_is_rejected(self):
        with self.assertRaises(LinkMlError) as raised:
            ohi_linkml.from_ossie("name: [unclosed")
        self.assertIn("Ossie ontology", str(raised.exception))

    def test_unknown_output_format_is_rejected(self):
        with self.assertRaises(LinkMlError) as raised:
            ohi_linkml.from_ossie(self.ONTOLOGY, format="toml")
        self.assertIn("toml", str(raised.exception))

    # A problem with the document is an issue, in the same shape as in a validation report.
    def test_a_document_that_is_not_an_ontology_is_a_conversion_error(self):
        with self.assertRaises(SchemaConversionError) as raised:
            ohi_linkml.from_ossie("- not\n- an ontology\n")
        issue = raised.exception.issue
        self.assertEqual("SchemaConversionError", issue["issue_type"])
        self.assertEqual("FATAL", issue["severity"])
        self.assertIn("Not a readable Ossie ontology", issue["reason"])
        self.assertEqual(issue["message"], str(raised.exception))
        self.assertIsInstance(raised.exception, LinkMlError)


class BuildInfoTest(unittest.TestCase):
    def test_build_info_describes_the_library(self):
        info = ohi_linkml.build_info()
        # The versions depend on which library was built, so only their shape is checked.
        self.assertTrue(info["linkml_scala_version"])
        self.assertRegex(info["metamodel_version"], r"^\d+\.\d+\.\d+$")
        self.assertTrue(info["scala_version"].startswith("3."))
        self.assertEqual("SCALA_NATIVE", info["platform"])

    def test_build_info_reports_the_abi_it_was_checked_against(self):
        from ohi_linkml._runtime import _EXPECTED_ABI_VERSION

        self.assertEqual(_EXPECTED_ABI_VERSION, ohi_linkml.build_info()["abi_version"])

    def test_build_info_does_not_claim_components_the_library_lacks(self):
        info = ohi_linkml.build_info()
        # The shared library is not a Scala.js build.
        self.assertNotIn("scala_js_version", info)


class LoadTest(unittest.TestCase):
    def test_load_string(self):
        with ohi_linkml.load_string(PERSON) as loaded:
            self.assertEqual([], loaded.issues(ohi_linkml.FATAL))
            self.assertIn("Person", loaded.json_schema())

    def test_load_file(self):
        model = REPO_ROOT / "tests" / "resources" / "models" / "basic" / "model.yaml"
        with ohi_linkml.load_file(model) as loaded:
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
        with ohi_linkml.load_string(root, {"shapes.yaml": shapes}) as loaded:
            generated = loaded.json_schema()
            self.assertIn("Drawing", generated)
            self.assertIn("Square", generated)

    def test_load_string_finds_keys_written_without_the_yaml_extension(self):
        # `- shapes` is looked up as "shapes.yaml", and keys get the same extension treatment, so
        # both spellings work.
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
        with ohi_linkml.load_string(root, {"shapes": shapes}) as loaded:
            self.assertEqual([], loaded.issues(ohi_linkml.FATAL))
            self.assertIn("Square", loaded.json_schema())

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
        with ohi_linkml.load_path("root.yaml", imports) as loaded:
            self.assertEqual([], loaded.issues(ohi_linkml.FATAL))
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
            ohi_linkml.load_string(root, {})
        self.assertIn("nowhere", json.dumps(caught.exception.report))

    def test_import_map_loads_a_curie_import_from_another_location(self):
        root = schema(
            "root",
            """
            prefixes:
              shapes: https://example.org/shapes/
            imports:
              - linkml:types
              - shapes:core
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
        imports = {"root.yaml": root, "deps/shapes.yaml": shapes}
        import_map = {"https://example.org/shapes/core": "deps/shapes.yaml"}
        with ohi_linkml.load_path("root.yaml", imports, import_map=import_map) as loaded:
            self.assertEqual([], loaded.issues(ohi_linkml.FATAL))
            self.assertIn("Square", loaded.json_schema())
        with tempfile.TemporaryDirectory() as tmp:
            map_file = Path(tmp) / "map.json"
            map_file.write_text(json.dumps(import_map), encoding="utf-8")
            for given in (map_file, str(map_file)):
                with ohi_linkml.load_path("root.yaml", imports, import_map=given) as loaded:
                    self.assertIn("Square", loaded.json_schema())

    def test_import_map_with_a_curie_key_is_reported(self):
        with self.assertRaises(SchemaLoadError) as caught:
            ohi_linkml.load_string(PERSON, {}, import_map={"linkml:types": "types.yaml"})
        self.assertIn("is a CURIE", json.dumps(caught.exception.report))

    def test_import_map_file_that_is_not_an_object_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            map_file = Path(tmp) / "map.json"
            map_file.write_text("[]", encoding="utf-8")
            with self.assertRaises(ValueError) as caught:
                ohi_linkml.load_string(PERSON, {}, import_map=map_file)
            self.assertIn("not a JSON object", str(caught.exception))

    def test_fatal_problems_raise_with_a_report(self):
        with self.assertRaises(SchemaLoadError) as caught:
            ohi_linkml.load_string(BROKEN)
        issues = caught.exception.report["issues"]
        self.assertEqual([ohi_linkml.FATAL], sorted({issue["severity"] for issue in issues}))
        self.assertIn("NoSuchThing", json.dumps(issues))

    def test_unparseable_yaml_raises(self):
        with self.assertRaises(SchemaLoadError):
            ohi_linkml.load_string("this: is: not: a: schema:")

    def test_load_needs_a_schema_or_a_path(self):
        with self.assertRaises(LinkMlError):
            ohi_linkml.runtime().load_string(None, None)


class GeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = ohi_linkml.load_string(PERSON)

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
        # Turtle by default: prefixed, with the property shapes inlined as `[ ... ]`.
        self.assertIn("PREFIX sh: <http://www.w3.org/ns/shacl#>", generated)
        self.assertIn("a sh:NodeShape", generated)
        self.assertIn("sh:property [", generated)
        self.assertIn("<https://example.org/person/Person>", generated)

    def test_shacl_ntriples(self):
        generated = self.schema.shacl(format="nt")
        self.assertIn("http://www.w3.org/ns/shacl#NodeShape", generated)
        self.assertIn("https://example.org/person/Person", generated)
        # N-Triples: one statement per line, each ending in a dot.
        for line in generated.splitlines():
            self.assertTrue(line.endswith("."), line)

    def test_shacl_open_drops_the_closed_constraint(self):
        self.assertIn("sh:closed true", self.schema.shacl())
        self.assertNotIn("sh:closed true", self.schema.shacl(open=True))

    def test_options_still_apply_alongside_an_explicit_format(self):
        self.assertIn('shacl#closed> "true"', self.schema.shacl(format="nt"))
        self.assertNotIn('shacl#closed> "true"', self.schema.shacl(open=True, format="nt"))

    def test_rdfs(self):
        generated = self.schema.rdfs()
        self.assertIn("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>", generated)
        self.assertIn("a rdfs:Class", generated)

    def test_rdfs_ntriples(self):
        self.assertIn("rdf-schema#Class", self.schema.rdfs(format="nt"))

    def test_rdf_format_defaults_to_turtle(self):
        self.assertEqual(self.schema.shacl(), self.schema.shacl(format="ttl"))
        self.assertNotEqual(self.schema.shacl(format="ttl"), self.schema.shacl(format="nt"))

    def test_unknown_rdf_format_is_rejected(self):
        for generator in (self.schema.shacl, self.schema.rdfs):
            with self.assertRaises(ohi_linkml.LinkMlError) as raised:
                generator(format="n3")
            self.assertIn("n3", str(raised.exception))

    def test_linkml(self):
        # Deriving pushes inherited and referenced slots down into each class' attributes.
        self.assertIn("attributes:", self.schema.linkml())
        self.assertIn('"attributes"', self.schema.linkml(format="json"))

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
        with ohi_linkml.load_string(unreachable) as loaded:
            self.assertIn("Orphan", loaded.linkml(pruning_mode="skip"))
            self.assertNotIn("Orphan", loaded.linkml(pruning_mode="tree_root"))

    def test_frictionless(self):
        files = self.schema.frictionless()
        self.assertIn("datapackage.json", files)
        self.assertIn("resources", json.loads(files["datapackage.json"]))
        schemas = [name for name in files if name.startswith("schemas/")]
        self.assertTrue(schemas, f"no table schemas in {sorted(files)}")
        self.assertIn("fields", json.loads(files[schemas[0]]))

    def test_frictionless_skips_classes_without_identifier(self):
        # Person is the tree root, so it stays a table even without an identifier slot.
        # Animal has neither, so it goes.
        files = self.schema.frictionless(skip_classes_without_identifier=True)
        self.assertIn("schemas/person.json", files)
        self.assertNotIn("schemas/animal.json", files)

    def test_graphql(self):
        self.assertIn("type Person", self.schema.graphql())

    def test_typescript(self):
        self.assertIn("export interface Person {", self.schema.typescript())

    def test_typescript_in_result_mode(self):
        self.assertIn(
            "export interface Person {",
            self.schema.typescript(error_handling="result"),
        )

    def test_unknown_typescript_error_handling(self):
        with self.assertRaises(LinkMlError) as caught:
            self.schema.typescript(error_handling="vigorously")
        self.assertIn("vigorously", str(caught.exception))

    def test_pydantic(self):
        self.assertIn("class Person(LinkMLModel):", self.schema.pydantic())

    def test_owl(self):
        generated = self.schema.owl()
        # Turtle by default, with classes as OWL classes.
        self.assertIn("a owl:Class", generated)
        self.assertIn("owl:Ontology", generated)
        self.assertIn("> .", self.schema.owl(format="nt"))

    def test_owl_round_trips_through_from_owl(self):
        def statements(nt):
            # The imported schema declares the linkml prefix, which the original does not, and
            # prefixes are written as sh:declare.
            return [line for line in nt.splitlines() if "http://www.w3.org/ns/shacl#" not in line]

        ontology = self.schema.owl()
        with ohi_linkml.load_string(ohi_linkml.from_owl(ontology)["schema"]) as reloaded:
            self.assertEqual(len(statements(self.schema.owl(format="nt"))), len(statements(reloaded.owl(format="nt"))))

    def test_er_diagram(self):
        generated = self.schema.er_diagram()
        self.assertIn("erDiagram", generated)
        # Type-ranged slots become attributes; optional ones get a trailing '?' on their type.
        self.assertIn("string? species", generated)
        self.assertNotIn("string? species", self.schema.er_diagram(optional_marker=False))
        # Class-ranged slots become relationship lines instead.
        self.assertIn('Person ||--o| Animal : "pet"', generated)

    def test_stats(self):
        stats = self.schema.stats()
        self.assertEqual("schema", stats["pruning_mode"])
        self.assertEqual(2, stats["elements"]["classes"]["count"])
        self.assertEqual({"direct": 1, "transitive": 1}, stats["imports"])
        self.assertIn("", stats["languages"])
        self.assertEqual("tree_root", self.schema.stats(pruning_mode="tree_root")["pruning_mode"])

    def test_ossie(self):
        generated = self.schema.ossie()
        # Classes become entity types, and slots become relationships under them.
        self.assertIn("concept: Person", generated)
        self.assertIn("type: EntityType", generated)
        # YAML by default, and the version the Ossie ontology spec pins.
        self.assertIn("version: 0.2.0.dev0", generated)
        as_json = json.loads(self.schema.ossie(format="json"))
        self.assertEqual("0.2.0.dev0", as_json["version"])
        self.assertIn("Person", [c["concept"] for c in as_json["ontology"]])

    def test_ossie_unknown_output_format_is_rejected(self):
        with self.assertRaises(ohi_linkml.LinkMlError) as raised:
            self.schema.ossie(format="toml")
        self.assertIn("toml", str(raised.exception))

    def test_scala(self):
        files = self.schema.scala(package="com.example.model")
        self.assertIn("Person.scala", files)
        self.assertIn("package com.example.model", files["Person.scala"])

    def test_an_unexported_function_cannot_be_called(self):
        # Each generator is its own exported symbol now, so a name that does not exist fails at the
        # ctypes layer rather than being dispatched and rejected by the library.
        with self.assertRaises(AttributeError):
            ohi_linkml.runtime().document("linkml_cuneiform", self.schema._handle)

    def test_unknown_pruning_mode(self):
        with self.assertRaises(LinkMlError) as caught:
            self.schema.linkml(pruning_mode="vigorously")
        self.assertIn("vigorously", str(caught.exception))

    def test_a_uri_that_can_not_be_written_is_a_conversion_error(self):
        # `other` doesn't declare `ex`, so its class URI is the URI `ex:Imported`. The root
        # declares `ex`, so writing the schema would read it back as another URI.
        root = schema(
            "root",
            """
            prefixes:
              ex: https://example.org/
            imports:
              - linkml:types
              - other
            classes:
              Root:
                class_uri: ex:Root
            """,
        )
        other = schema(
            "other",
            """
            classes:
              Imported:
                class_uri: ex:Imported
            """,
        )
        with ohi_linkml.load_string(root, {"other.yaml": other}) as loaded:
            with self.assertRaises(SchemaConversionError) as raised:
                loaded.linkml()
        self.assertIn("'ex:Imported' can't be written", raised.exception.issue["reason"])

    def test_an_unknown_option_is_rejected_rather_than_ignored(self):
        with self.assertRaises(LinkMlError) as caught:
            ohi_linkml.runtime().document(
                "linkml_json_schema", self.schema._handle, {"opne": True}
            )
        self.assertIn("opne", str(caught.exception))


class LintTest(unittest.TestCase):
    def test_a_clean_schema_has_no_issues(self):
        # Each element has a description, as one without it gets a warning.
        described = schema(
            "described",
            """
            description: A schema with one class
            classes:
              Person:
                description: A person
                tree_root: true
                attributes:
                  name:
                    description: The full name of the person
            """,
        )
        with ohi_linkml.load_string(described) as loaded:
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
        with ohi_linkml.load_string(rootless) as loaded:
            # Loading checks only for fatal problems, so the warning is not in its report.
            self.assertEqual([], loaded.report["issues"])
            warnings = loaded.issues(ohi_linkml.WARNING)
            self.assertTrue(warnings, "expected a warning about the missing tree root")
            self.assertTrue(all("message" in warning for warning in warnings))

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
        with ohi_linkml.load_string(rootless) as loaded:
            issues = loaded.lint(infer_messages=False)["issues"]
            self.assertTrue(issues)
            self.assertFalse(any("message" in issue for issue in issues))

    def test_issues_use_infer_messages_of_the_load(self):
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
        with ohi_linkml.load_string(rootless, infer_messages=False) as loaded:
            warnings = loaded.issues(ohi_linkml.WARNING)
            self.assertTrue(warnings)
            self.assertFalse(any("message" in warning for warning in warnings))

    def test_issues_lint_the_schema_once(self):
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
        with ohi_linkml.load_string(rootless) as loaded:
            calls = []
            lint = loaded.lint

            def counted_lint(**options):
                calls.append(options)
                return lint(**options)

            loaded.lint = counted_lint
            self.assertTrue(loaded.issues(ohi_linkml.WARNING))
            self.assertEqual([], loaded.issues(ohi_linkml.ERROR))
            self.assertEqual(1, len(calls))


class HandleTest(unittest.TestCase):
    def test_using_a_closed_schema_raises(self):
        loaded = ohi_linkml.load_string(PERSON)
        loaded.close()
        with self.assertRaises(LinkMlError):
            loaded.json_schema()

    def test_closing_twice_is_fine(self):
        loaded = ohi_linkml.load_string(PERSON)
        loaded.close()
        loaded.close()

    def test_repr_shows_the_handle_and_no_issue_count(self):
        loaded = ohi_linkml.load_string(PERSON)
        self.assertEqual(f"<ohi_linkml.Schema handle={loaded._handle}>", repr(loaded))
        loaded.close()
        self.assertEqual("<ohi_linkml.Schema closed>", repr(loaded))

    def test_an_unknown_handle_raises(self):
        with self.assertRaises(LinkMlError) as caught:
            ohi_linkml.runtime().document("linkml_lint", 999_999)
        self.assertIn("999999", str(caught.exception))

    def test_handle_zero_raises(self):
        # 0 is what loading returns when it refused, so it must never be usable.
        with self.assertRaises(LinkMlError):
            ohi_linkml.runtime().document("linkml_lint", 0)

    def test_handles_are_independent(self):
        with ohi_linkml.load_string(PERSON) as first:
            with ohi_linkml.load_string(FIXED) as second:
                self.assertIn("Person", first.json_schema())
                self.assertIn("Thing", second.json_schema())

    def test_exiting_with_a_schema_still_open_does_not_hang(self):
        # The schema's finaliser runs during shutdown, after Python has stopped the worker thread
        # for good. A close that waited for the worker then would never return.
        script = textwrap.dedent(
            f"""
            import ohi_linkml
            loaded = ohi_linkml.load_string({PERSON!r})
            loaded.json_schema()
            """
        )
        # Import the same package this test did, whether from a checkout or an installed wheel.
        env = dict(os.environ, PYTHONPATH=str(Path(ohi_linkml.__file__).resolve().parents[1]))
        finished = subprocess.run(
            [sys.executable, "-c", script], env=env, capture_output=True, text=True, timeout=60
        )
        self.assertEqual(0, finished.returncode, finished.stderr)


class RuntimeTest(unittest.TestCase):
    def test_the_runtime_is_shared(self):
        self.assertIs(ohi_linkml.runtime(), ohi_linkml.runtime())

    def test_library_path_points_at_a_real_file(self):
        self.assertTrue(ohi_linkml.library_path().is_file())

    def test_other_threads_can_call_in(self):
        # Every thread has to attach to the isolate before its first call. Getting that wrong takes
        # the whole process down rather than raising, so this test either passes or nothing does.
        results: list[str] = []
        errors: list[BaseException] = []

        def work():
            try:
                with ohi_linkml.load_string(PERSON) as loaded:
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
        # The generators UTF-8 encode straight into unmanaged memory, so this covers the encoder as
        # well as the trip through C. 🐍 is outside the BMP, which means a surrogate pair in the
        # generator's char data and a four-byte sequence on the wire -- encode that as two
        # three-byte sequences (CESU-8) and Python would refuse to decode it.
        unicode_schema = schema(
            "unicode",
            """
            classes:
              Terrarium:
                tree_root: true
                attributes:
                  wąż🐍:
                    description: "grzegorz brzęczyszczykiewicz 🐍"
                    range: string
            """,
        )
        # The emoji is in the slot name, not just the description, because the ER diagram renders
        # names and types but no descriptions.
        with ohi_linkml.load_string(unicode_schema) as loaded:
            for name in (
                "linkml",
                "graphql",
                "typescript",
                "pydantic",
                "translation",
                "json_schema",
                "ossie",
            ):
                with self.subTest(generator=name):
                    generated = getattr(loaded, name)()
                    self.assertIn("🐍", generated)
                    self.assertIn("Terrarium", generated)

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
        with ohi_linkml.load_string(schema("big", f"classes:\n{classes}")) as loaded:
            # N-Triples rather than the default, because one complete statement per line is what
            # makes a truncated copy detectable at all.
            generated = loaded.shacl(format="nt")
        self.assertGreater(len(generated), 1_000_000)
        # Every line is a complete statement, so nothing was cut short mid-copy.
        self.assertTrue(all(line.endswith(".") for line in generated.splitlines()))


if __name__ == "__main__":
    unittest.main()
