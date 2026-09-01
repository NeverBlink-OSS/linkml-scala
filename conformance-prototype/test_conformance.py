"""Pytest front-end for the conformance runner, so each manifest entry is its own test.

This is the equivalent of the ScalaTest spec in ConformanceRunner.scala.
Run with: .venv/bin/python -m pytest conformance-prototype
"""

from __future__ import annotations

import pytest
from linkml_runtime import SchemaView

import runner

manifest = runner.load_manifest()
schema_path = runner.RESOURCES / manifest.schema


@pytest.fixture(scope="module")
def schema_view() -> SchemaView:
    return SchemaView(str(schema_path))


@pytest.mark.parametrize(
    "test",
    manifest.entries,
    ids=[runner.test_name(index, test) for index, test in enumerate(manifest.entries)],
)
def test_manifest_entry(test, schema_view: SchemaView) -> None:
    runner.run_test(test, schema_path, schema_view)
