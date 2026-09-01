"""Python port of the Scala LinkML conformance test runner.

Mirrors generator/test/src-jvm/eu/neverblink/linkml/generator/conformance/
ConformanceRunner.scala: it loads a manifest into the classes generated from
model/conformance-ontology.yaml, runs each entry's action, and checks the
result against the entry's assertion.

Run it directly (`python runner.py`) for a plain report, or via pytest
(see test_conformance.py).
"""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from jsonasobj2 import JsonObj, as_dict
from jsonschema.validators import Draft202012Validator, validator_for
from linkml.generators.jsonschemagen import JsonSchemaGenerator
from linkml.generators.yamlgen import YAMLGenerator
from linkml.linter.linter import Linter
from linkml_runtime import SchemaView
from linkml_runtime.loaders import yaml_loader

sys.path.insert(0, str(Path(__file__).parent))

import generated as conformance  # noqa: E402

# Hard-coded for now, like the Scala runner.
RESOURCES = Path("/home/kacper/NeverBlink/linkml-scala/generator/test/resources")
MANIFEST = RESOURCES / "conformance" / "manifest.yaml"


def load_manifest(path: Path = MANIFEST) -> conformance.Manifest:
    """Read the manifest YAML into the generated Manifest class.

    The `type` slot is a type designator, so linkml_runtime picks the right
    Action/Assertion subclass for each entry on its own.
    """
    return yaml_loader.load(str(path), conformance.Manifest)


def test_name(index: int, test: conformance.Test) -> str:
    name = f"{type(test.action).__name__} -> {type(test.assertion).__name__}"
    if test.title:
        name = f"{name} ({test.title})"
    return f"[{index}] {name}"


# --- actions ---------------------------------------------------------------


def run_action(action: conformance.Action, schema_path: Path, schema_view: SchemaView) -> str:
    """Run an action and return its output as a string, as the Scala runner does."""
    if isinstance(action, conformance.DeriveAction):
        return YAMLGenerator(str(schema_path)).serialize()
    if isinstance(action, conformance.JsonSchemaGenerate):
        return JsonSchemaGenerator(str(schema_path)).serialize()
    if isinstance(action, conformance.LintAction):
        problems = Linter().lint(str(schema_path))
        return "\n".join(problem.message for problem in problems)
    if isinstance(action, conformance.LoadAction):
        assert schema_view is not None
        return ""
    raise NotImplementedError(f"unsupported action: {type(action).__name__}")


# --- assertions ------------------------------------------------------------


def to_plain(value: Any) -> Any:
    """Turn loader objects into plain dicts/lists so `==` compares like-for-like."""
    if isinstance(value, JsonObj):
        return to_plain(as_dict(value))
    if isinstance(value, dict):
        return {key: to_plain(item) for key, item in value.items()}
    if isinstance(value, list):
        return [to_plain(item) for item in value]
    return value


def step_path(document: Any, path: str) -> Any:
    """Walk a slash-separated path; a segment that parses as an int indexes a list."""
    current = document
    for segment in path.split("/"):
        try:
            key: Any = int(segment)
        except ValueError:
            key = segment
        try:
            current = current[key]
        except (KeyError, IndexError, TypeError):
            raise AssertionError(f"Could not access the value at {path}")
    return current


def validation_errors(schema_text: str, instance_path: Path) -> list:
    schema = json.loads(schema_text)
    instance = json.loads(instance_path.read_text())
    validator_cls = validator_for(schema, default=Draft202012Validator)
    validator = validator_cls(schema, format_checker=validator_cls.FORMAT_CHECKER)
    return list(validator.iter_errors(instance))


def check_assertion(assertion: conformance.Assertion, result: str) -> None:
    """Raise AssertionError if the action's result doesn't satisfy the assertion."""
    if isinstance(assertion, conformance.JsonPathAssertion):
        actual = step_path(json.loads(result), assertion.path)
        expected = to_plain(assertion.value)
        assert to_plain(actual) == expected, (
            f"at {assertion.path}: expected {expected!r}, got {actual!r}"
        )

    elif isinstance(assertion, conformance.JsonSchemaAccepts):
        errors = validation_errors(result, RESOURCES / assertion.instance)
        assert not errors, "\n".join(
            f"{assertion.instance} should validate but: {error.message}" for error in errors
        )

    elif isinstance(assertion, conformance.JsonSchemaRejects):
        errors = validation_errors(result, RESOURCES / assertion.instance)
        assert errors, f"{assertion.instance} should not validate, but it did"

    elif isinstance(assertion, conformance.StringAssertion):
        missing = [part for part in (assertion.includes or []) if part not in result]
        assert not missing, f"output is missing: {missing}"

    elif isinstance(assertion, conformance.LoadsAssertion):
        pass

    else:
        raise NotImplementedError(f"unsupported assertion: {type(assertion).__name__}")


# --- driver ----------------------------------------------------------------


@dataclass
class Result:
    name: str
    passed: bool
    error: str = ""


def run_test(test: conformance.Test, schema_path: Path, schema_view: SchemaView) -> None:
    check_assertion(test.assertion, run_action(test.action, schema_path, schema_view))


def run_all(manifest: conformance.Manifest | None = None) -> list[Result]:
    manifest = manifest or load_manifest()
    schema_path = RESOURCES / manifest.schema
    schema_view = SchemaView(str(schema_path))

    results = []
    for index, test in enumerate(manifest.entries):
        name = test_name(index, test)
        try:
            run_test(test, schema_path, schema_view)
            results.append(Result(name, True))
        except Exception as error:  # a failed assertion or a broken action
            results.append(Result(name, False, f"{type(error).__name__}: {error}"))
    return results


def main() -> int:
    manifest = load_manifest()
    print(f"manifest: {manifest.name} ({MANIFEST})")
    print(f"schema:   {manifest.schema}\n")

    results = run_all(manifest)
    for result in results:
        print(f"{'PASS' if result.passed else 'FAIL'}  {result.name}")
        if not result.passed:
            for line in result.error.splitlines():
                print(f"        {line}")

    failed = [result for result in results if not result.passed]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
