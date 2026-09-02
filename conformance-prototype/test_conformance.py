"""Pytest front-end for the conformance runner, so each manifest entry is its own test.

This is the equivalent of the ScalaTest spec in ConformanceRunner.scala.
Run with: .venv/bin/python -m pytest conformance-prototype
"""

from __future__ import annotations

import pytest

import runner

manifest = runner.load_manifest()
tests = runner.entries(manifest)


@pytest.mark.parametrize("test", tests, ids=[test.name for test in tests])
def test_manifest_entry(test) -> None:
    runner.run_test(test)
