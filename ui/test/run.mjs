// Runs the UI tests: `npm test`, and the Mill `ui.check` task. Each test file is bundled with
// esbuild into dist/test/ first, so the tests run with any Node.js version that has `node --test`.
import * as esbuild from "esbuild";
import { spawnSync } from "node:child_process";

const TESTS = ["test/markdown.test.ts"];
const OUT = "dist/test";

await esbuild.build({
  entryPoints: TESTS,
  bundle: true,
  format: "esm",
  platform: "node",
  target: "node16",
  outdir: OUT,
  outExtension: { ".js": ".mjs" },
  // node:test and node:assert come with Node.js.
  external: ["node:*"],
  logLevel: "warning",
});

const outputs = TESTS.map((t) => `${OUT}/${t.slice("test/".length).replace(/\.ts$/, ".mjs")}`);
const run = spawnSync(process.execPath, ["--test", ...outputs], { stdio: "inherit" });
process.exit(run.status ?? 1);
