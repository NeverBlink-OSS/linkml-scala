// Packs and installs the built package into a throwaway project, then runs the
// packaged README's JS examples (skipping blocks tagged `no-test`) and, if a TS
// usage sample is given, type-checks it against the generated index.d.ts.
//
//   node verify-package.mjs <package-dir> [usage-sample.ts]
//
// Non-zero exit = install failed, an example threw, or the sample did not compile.

import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, readdirSync, copyFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const pkgDir = process.argv[2];
const usageSample = process.argv[3];
if (!pkgDir) {
  console.error("Usage: node verify-package.mjs <package-dir> [usage-sample.ts]");
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

// 2. Type-check the usage sample against the generated declarations.
if (usageSample && existsSync(usageSample)) {
  console.log("Type-checking usage sample against generated index.d.ts");
  copyFileSync(usageSample, join(work, "usage.ts"));
  try {
    run("npm", ["install", "--no-save", "--no-audit", "--no-fund", "typescript"], work);
    run(
      "npx",
      ["tsc", "--noEmit", "--strict", "--module", "nodenext", "--moduleResolution", "nodenext", "usage.ts"],
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
