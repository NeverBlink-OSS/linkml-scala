// Tests for targets.ts. Run with `npm test` (see run.mjs).
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { IMPORTERS, TARGETS } from "../src/targets.js";

// npm runs the tests from ui/.
const DOCS = "../website/src/content/docs";

test("every generator and importer links to a page in the docs", () => {
  for (const step of [...TARGETS, ...IMPORTERS]) {
    const page = `${DOCS}/${step.docs.replace(/\/$/, "")}`;
    const found = [".md", ".mdx", "/index.md", "/index.mdx"].some((ext) => existsSync(page + ext));
    assert.ok(found, `${step.label}: no docs page at ${page}`);
  }
});
