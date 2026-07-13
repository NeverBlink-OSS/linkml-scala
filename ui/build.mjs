// esbuild bundler for the playground UI. Invoked by `npm run build` and by the
// Mill `ui`/`uiBundle` tasks. Bundles app.ts + CodeMirror into dist/app.js; the
// (large, separately built) Scala.js bundle is loaded at runtime, not bundled.
import * as esbuild from "esbuild";

const options = {
  entryPoints: ["app.ts"],
  bundle: true,
  format: "esm",
  target: "es2020",
  outfile: "dist/app.js",
  sourcemap: true,
  minify: true,
  logLevel: "info",
  // The Scala.js bundle is large and built separately by Mill; load it at
  // runtime instead of bundling it in.
  external: ["/out/generator/js/fullLinkJS.dest/main.js"],
};

if (process.argv.includes("--watch")) {
  const ctx = await esbuild.context(options);
  await ctx.watch();
  console.log("esbuild watching…");
} else {
  await esbuild.build(options);
}
