# Contributing to LinkML-Scala

Thanks for your interest in LinkML-Scala! We're happy to see the project used and  improved by others.

A note on expectations: we don't yet have a Contributor License Agreement (CLA) process in place, so we can't merge substantial external contributions just yet. You are very welcome to open issues and pull requests – bug reports and feature requests are always appreciated and help us a lot. If you're serious about contributing something larger, please [reach out to us](mailto:contact@neverblink.eu) first so we can sort out the details together.

## Building the project

LinkML-Scala uses [mill](https://mill-build.org/) as the build tool. A bootstrap script is included with this repo (`./mill`), so no additional setup is required for CLI usage. In IntelliJ, [you have to import the project as BSP](https://mill-build.org/mill/cli/installation-ide.html#_intellij) and you are good to go.

Common tasks with mill:

- Run CLI directly: `./mill cli.jvm.run --help`
- Start the browser UI: `./mill ui`
- Scan the mill project structure: `./mill resolve _`
- Compile all modules: `./mill __.compile`
- Run all tests: `./mill __.test` (prefer specific test running for faster feedback, like `./mill generator.jvm.test`)
- Run the JMH benchmarks: `./mill benchmark.runJmh` (see [Benchmarks](#benchmarks))
- Lint the project `./mill lint` (scalafix + scalafmt)
- Re-generate the metamodel classes `./mill metamodel.regenerate`
- Fetch the metamodel definitions from [linkml/linkml-model](https://github.com/linkml/linkml-model) `./mill metamodel.definitions`
- Publish artifacts locally: `./mill __.publishLocal`
- Assembly runnable .jar: `./mill cli.jvm.assembly`
- Build native binary: `./mill cli.jvm.nativeImage` (requires Coursier (cs) to be installed)
- Assemble the npm package: `./mill generator.js.npmPackage` (TS declarations are generated from the Scala facade)
- Verify the npm package (README examples run + types compile): `./mill generator.js.verifyPackage` (requires Node.js and npm)

### Playground UI

The browser playground lives in [`ui/`](ui/) — a TypeScript app (CodeMirror editors) bundled with [esbuild](https://esbuild.github.io/). It loads the Scala.js generator bundle at runtime. **Node.js and npm must be on your `PATH`** for the tasks below. The first run installs the npm dependencies automatically.

- Serve it locally: `./mill ui` – builds the Scala.js bundle **and** the UI bundle, then serves at <http://localhost:8000/ui/>
- Build the UI bundle only: `./mill uiBundle` → `ui/dist/app.js`
- Type-check and bundle (what CI runs): `./mill uiCheck`
- Regenerate the LinkML API types: `./mill uiTypes` – run after changing `generator/src-js/.../LinkMlJsApi.scala`

`ui/linkml.d.ts` is generated from the Scala facade and **committed** (so editors resolve types without a build). CI regenerates it and fails if it's stale, so re-run `./mill uiTypes` and commit the result when the facade changes.

You can also work in `ui/` directly with npm:

```shell
cd ui
npm install       # first-time setup
npm run watch     # esbuild, rebuilds on change
npm run typecheck # tsc --noEmit
npm run build     # one-off bundle
```

## Benchmarks

Performance benchmarks live in [`benchmark/`](benchmark/) and use [JMH](https://github.com/openjdk/jmh) via the mill JMH plugin. The module is JVM-only and is never published.

- Run all benchmarks: `./mill benchmark.runJmh`
- Run a subset (regex over benchmark names): `./mill benchmark.runJmh ".*jsonSchema.*"`
- List available benchmarks: `./mill benchmark.listJmhBenchmarks`

## Releasing with GitHub UI

1. Go to Releases -> Draft a new release
2. Press "Select tag" list
3. Enter the tag name in form `v0.2.3`
4. Press "Generate Release Notes" button
5. Select "Pre-release" or "Release" list item
6. Press "Publish Release" button
