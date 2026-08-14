// esbuild bundler for the playground UI. Invoked by `npm run build` and by the
// Mill `ui`/`uiBundle` tasks. Bundles app.ts + CodeMirror into dist/app.js and the
// generation worker into dist/worker.js, and minifies the (large, separately built)
// Scala.js bundle into dist/linkml.js.
import * as esbuild from "esbuild";
import { existsSync, rmSync } from "node:fs";

// The Scala.js bundle, emitted by Mill's `generator.js.fullLinkJS`. We run esbuild
// over it to strip whitespace and rename locals.
// Loaded at runtime by app.js as a sibling `dist/linkml.js`.
const SCALA_BUNDLE = "../out/generator/js/fullLinkJS.dest/main.js";

// app.ts is the page; worker.ts is the generation worker it spawns as a sibling
// dist/worker.js. Both are emitted into dist/ by outdir.
const appOptions = {
  entryPoints: ["app.ts", "worker.ts"],
  bundle: true,
  format: "esm",
  target: "es2020",
  outdir: "dist",
  sourcemap: true,
  minify: true,
  logLevel: "info",
  // Loaded at runtime from dist/linkml.js (see minifyScalaBundle) and dist/mermaid/mermaid.js (see
  // mermaidOptions); keep esbuild from inlining either.
  external: ["./linkml.js", "./mermaid/mermaid.js"],
};

// Mermaid, for rendering the ER diagram, bundled into dist/mermaid/.
//
// `splitting` is what makes this worth doing: mermaid dynamically imports one chunk per diagram
// type, and splitting keeps those as separate files.
//
// Mermaid's own prebuilt ESM has the same shape, but its files are `.mjs`, and the JDK file server
// behind `./mill ui` serves those as application/octet-stream - which browsers refuse to execute as
// a module. Re-bundling gets us `.js`, servable by anything.
const mermaidOptions = {
  entryPoints: ["mermaid"],
  bundle: true,
  splitting: true,
  format: "esm",
  target: "es2020",
  outdir: "dist/mermaid",
  minify: true,
  logLevel: "info",
};

/** Chunk names carry a content hash, so a version bump leaves the previous set behind. esbuild does
 * not clean its outdir, so do it here rather than deploy an ever-growing pile of dead chunks. */
function buildMermaid() {
  rmSync("dist/mermaid", { recursive: true, force: true });
  return esbuild.build(mermaidOptions);
}

// Minify the Scala.js bundle into dist/linkml.js. Skipped (with a warning) when
// the bundle hasn't been built yet, so `npm run build`/typecheck still work on
// their own; the Mill `ui`/`uiBundle`/deploy flows build fullLinkJS first.
async function minifyScalaBundle() {
  if (!existsSync(SCALA_BUNDLE)) {
    console.warn(
      `Scala.js bundle not found at ${SCALA_BUNDLE}; skipping dist/linkml.js ` +
        "(run `./mill generator.js.fullLinkJS` first).",
    );
    return;
  }
  await esbuild.build({
    entryPoints: [SCALA_BUNDLE],
    format: "esm",
    outfile: "dist/linkml.js",
    minify: true,
    logLevel: "info",
    // The Scala.js source map points at .scala sources that aren't served.
    sourcemap: false,
  });
}

if (process.argv.includes("--watch")) {
  await Promise.all([minifyScalaBundle(), buildMermaid()]);
  const ctx = await esbuild.context(appOptions);
  await ctx.watch();
  console.log("esbuild watching…");
} else {
  await Promise.all([
    esbuild.build(appOptions),
    buildMermaid(),
    minifyScalaBundle(),
  ]);
}
