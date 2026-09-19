// Packs and installs the built package into a throwaway project, then runs the
// packaged README's JS examples (skipping blocks tagged `no-test`) and, if a TS
// usage sample is given, type-checks it against the generated index.d.ts.
//
//   node verify-package.mjs <package-dir> [usage-sample.ts] [required-last.d.ts]
//
// Non-zero exit = install failed, an example threw, or the sample did not compile.

import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, readdirSync, copyFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const pkgDir = process.argv[2];
const usageSample = process.argv[3];
const requiredLastDeclaration = process.argv[4];
if (!pkgDir) {
  console.error("Usage: node verify-package.mjs <package-dir> [usage-sample.ts] [required-last.d.ts]");
  process.exit(2);
}

function run(cmd, args, cwd) {
  return execFileSync(cmd, args, { cwd, encoding: "utf8" });
}

// Extract runnable JS blocks from the packaged README.
const readme = readFileSync(join(pkgDir, "README.md"), "utf8");
const blocks = [];
const fence = /```(\w+)([^\n]*)\n([\s\S]*?)```/g;
let match;
while ((match = fence.exec(readme)) !== null) {
  const lang = match[1].toLowerCase();
  const info = match[2] || "";
  if ((lang === "js" || lang === "javascript") && !info.includes("no-test")) {
    blocks.push(match[3]);
  }
}
if (blocks.length === 0) {
  console.error("No runnable ```js blocks found in README.md");
  process.exit(1);
}
console.log(`Found ${blocks.length} runnable JS example(s) in README.md`);

// Pack the package and install it into a throwaway consumer project, so imports
// resolve `@neverblink/linkml` through the published `exports` map.
const work = mkdtempSync(join(tmpdir(), "linkml-pkg-"));
run("npm", ["pack", "--pack-destination", work], pkgDir);
const tarball = readdirSync(work).find((f) => f.endsWith(".tgz"));
run("npm", ["init", "-y"], work);
run("npm", ["install", "--no-audit", "--no-fund", join(work, tarball)], work);

let failures = 0;

// 1. Run each README example verbatim.
blocks.forEach((code, i) => {
  const file = join(work, `example-${i + 1}.mjs`);
  writeFileSync(file, code);
  try {
    run("node", [file], work);
    console.log(`  example ${i + 1}: OK`);
  } catch (e) {
    failures++;
    console.error(`  example ${i + 1}: FAILED\n${e.stdout || ""}${e.stderr || ""}`);
  }
});

// Check interface defaults against the installed bundle and the shared pruning fixture.
const pruningImports = Object.fromEntries(
  ["model.yaml", "imported.yaml"].map((file) => [
    file,
    readFileSync(new URL(`../../tests/resources/models/pruning/${file}`, import.meta.url), "utf8"),
  ]),
);
try {
  run("node", ["--input-type=module", "--eval", `
    import assert from "node:assert/strict";
    import { LinkML } from "@neverblink/linkml";

    const { view, report } = LinkML.loadFromPath("model.yaml", ${JSON.stringify(pruningImports)});
    assert.ok(view, JSON.stringify(report));
    const classes = ["SomeClass", "NotTreeRootClass", "UnusedClass"];
    const generators = [
      ["linkml", (mode, root) => LinkML.linkml(view, mode, false, root), [true, false, false], classes],
      ["graphQl", (mode, root) => LinkML.graphQl(view, mode, root), [true, false, false], classes],
      ["erDiagram", (mode, root) => LinkML.erDiagram(view, mode, root), [true, false, false], classes],
      ["ossie", (mode, root) => LinkML.ossie(view, mode, root), [true, true, true], classes],
      ["frictionless", (mode, root) => JSON.stringify(LinkML.frictionless(view, mode, root)),
        [true, true, true], ["some_class", "not_tree_root_class", "unused_class"]],
    ];
    for (const [name, generate, defaults, markers] of generators) {
      for (const [mode, root, expected] of [
        [undefined, undefined, defaults],
        ["schema", undefined, [true, true, false]],
        ["treeRoot", "NotTreeRootClass", [false, true, false]],
      ]) {
        const output = generate(mode, root);
        assert.deepEqual(markers.map((marker) => output.includes(marker)), expected, name + ": " + mode);
      }
    }
    assert.match(LinkML.erDiagram(view), /string\\? some_slot/);
    assert.doesNotMatch(LinkML.erDiagram(view, "treeRoot", undefined, false), /string\\? some_slot/);
    assert.throws(() => LinkML.linkml(view, "bad-mode", false, undefined, "bad-format"), /Unknown pruning mode/);
    assert.throws(() => LinkML.ossie(view, "bad-mode", undefined, "bad-format"), /Unknown output format/);
    assert.throws(() => LinkML.linkml(null, "skip", false, undefined, "bad-format"), /Unknown output format/);
    assert.throws(() => LinkML.ossie(null, "bad-mode", undefined, "yaml"), TypeError);
  `], work);
  console.log("  generator option defaults and forwarding: OK");
} catch (e) {
  failures++;
  console.error(`  generator option defaults and forwarding: FAILED\n${e.stdout || ""}${e.stderr || ""}`);
}

// Type-check the usage sample against the generated declarations.
if (usageSample || requiredLastDeclaration) {
  console.log("Type-checking generated declarations");
  try {
    const samples = [];
    if (usageSample) {
      copyFileSync(usageSample, join(work, "usage.ts"));
      samples.push("usage.ts");
    }
    if (requiredLastDeclaration) {
      copyFileSync(requiredLastDeclaration, join(work, "ordering-types.d.ts"));
      writeFileSync(join(work, "required-last.ts"), `
import { LinkML, type SchemaView } from "./ordering-types.js";
declare const view: SchemaView;
const withDefaults: string = LinkML.shacl(view, undefined, undefined, "nt");
const withOptions: string = LinkML.shacl(view, false, true, "ttl");
// @ts-expect-error The final format parameter is required.
LinkML.shacl(view);
// @ts-expect-error Earlier arguments do not supply the required format.
LinkML.shacl(view, false, true);
void [withDefaults, withOptions];
`);
      samples.push("required-last.ts");
    }
    run("npm", ["install", "--no-save", "--no-audit", "--no-fund", "typescript@5.9.3"], work);
    run(
      "npx",
      ["tsc", "--noEmit", "--strict", "--module", "nodenext", "--moduleResolution", "nodenext", ...samples],
      work,
    );
    console.log("  type check: OK");
  } catch (e) {
    failures++;
    console.error(`  type check: FAILED\n${e.stdout || ""}${e.stderr || ""}`);
  }
}

if (failures > 0) {
  console.error(`\n${failures} package check(s) failed.`);
  process.exit(1);
}
console.log("\nPackage verified: README examples run and types compile.");
