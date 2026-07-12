// Verifies that the JavaScript examples in the packaged README.md actually run
// against the built `@neverblink/linkml` artifact, exactly as a consumer would.
//
// Usage:
//   node verify-readme.mjs <path-to-built-npm-package-dir>
//
// The package dir is the output of `./mill generator.js.npmPackage` (it contains
// package.json, main.js, index.d.ts and README.md). This script:
//   1. extracts every ```js / ```javascript fenced block from that README
//      (skipping any whose info string contains `no-test`);
//   2. packs the package with `npm pack` and installs the tarball into a
//      throwaway project — so imports resolve through the real `exports` map;
//   3. runs each example with Node.
//
// A non-zero exit code means the package failed to install or an example threw.
// Requires `node` and `npm` on the PATH.

import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const pkgDir = process.argv[2];
if (!pkgDir) {
  console.error("Usage: node verify-readme.mjs <package-dir>");
  process.exit(2);
}

function run(cmd, args, cwd) {
  return execFileSync(cmd, args, { cwd, encoding: "utf8" });
}

// 1. Extract runnable JS blocks from the packaged README.
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

// 2. Pack the package and install it into a throwaway consumer project, so the
//    examples resolve `@neverblink/linkml` through the published `exports` map.
const work = mkdtempSync(join(tmpdir(), "linkml-readme-"));
run("npm", ["pack", "--pack-destination", work], pkgDir);
const tarball = readdirSync(work).find((f) => f.endsWith(".tgz"));
run("npm", ["init", "-y"], work);
run("npm", ["install", "--no-audit", "--no-fund", join(work, tarball)], work);

// 3. Run each example verbatim.
let failures = 0;
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

if (failures > 0) {
  console.error(`\n${failures} README example(s) failed.`);
  process.exit(1);
}
console.log("\nAll README examples ran successfully.");
