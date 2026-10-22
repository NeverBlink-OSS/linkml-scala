# Contributing to LinkML-Scala

Thanks for your interest in LinkML-Scala! We're happy to see the project used and  improved by others.

A note on expectations: we don't yet have a Contributor License Agreement (CLA) process in place, so we can't merge substantial external contributions just yet. You are very welcome to open issues and pull requests – bug reports and feature requests are always appreciated and help us a lot. If you're serious about contributing something larger, please [reach out to us](mailto:contact@neverblink.eu) first so we can sort out the details together.

Want to chat with the maintainers, ask a question, or discuss an idea before opening a PR? **[Join our Discord](https://discord.gg/HGksVAJ6ss)**.

## Building the project

LinkML-Scala uses [mill](https://mill-build.org/) as the build tool. A bootstrap script is included with this repo (`./mill`), so no additional setup is required for CLI usage. In IntelliJ, [you have to import the project as BSP](https://mill-build.org/mill/cli/installation-ide.html#_intellij) and you are good to go.

Common tasks with mill:

- Run CLI directly: `./mill cli.jvm.run --help`
- Start the browser UI: `./mill ui.serve`
- Scan the mill project structure: `./mill resolve _`
- Compile all modules: `./mill __.compile`
- Run all tests: `./mill __.testForked` (prefer specific test running for faster feedback, like `./mill generator.jvm.test`).
- Run the JMH benchmarks: `./mill benchmark.runJmh` (see [Benchmarks](#benchmarks))
- Lint the project: `./mill lint` (scalafix + scalafmt)
- Re-generate all classes generated from LinkML models (metamodel, validation report, build info, OWL import config, conformance tests, Ohi client models): `./mill regenerate` (see [Generated models](#generated-models))
- Fetch the metamodel definitions from [NeverBlink-OSS/linkml-model](https://github.com/NeverBlink-OSS/linkml-model) `./mill metamodel.definitions`
- Publish artifacts locally: `./mill __.publishLocal`
- Assembly runnable .jar: `./mill cli.jvm.assembly`
- Build native binary: `./mill cli.jvm.nativeImage` (requires Coursier (cs) to be installed)
- Assemble the npm package: `./mill generator.js.npmPackage` (TS declarations are generated from the Scala facade)
- Verify the npm package (README examples run + types compile): `./mill generator.js.verifyPackage` (requires Node.js and npm)

### Generated models

The classes generated from each LinkML schema in this repo live in their own module, which extends `LinkMlModelModule` (in `mill-build/src`):

| Module                 | Schema                             | Other generated files                  |
|------------------------|------------------------------------|----------------------------------------|
| `metamodel`            | `linkml:meta`                      |                                        |
| `validation`           | `model/issue-types.yaml`           | `generator/npm/validation-report.d.ts` |
| `schemaview.buildinfo` | `model/build-info.yaml`            | `generator/npm/build-info.d.ts`        |
| `generator.owlconfig`  | `model/owl-import-config.yaml`     |                                        |
| `tests.conformance`    | the conformance suite's model.yaml |                                        |

The generated classes are committed, because generating them needs the CLI, which depends on them.

#### Regenerating

Regenerate the classes after you change one of the schemas, the Scala or TypeScript generator, or the metamodel version:

- All models: `./mill regenerate`. It runs `regenerate`, `fix` and `reformat` on every model module, and then regenerates the Ohi models (see [ohi/README.md](ohi/README.md)).
- One model: run its `regenerate` command, and then format the result, as separate commands:
  ```shell
  ./mill validation.regenerate
  ./mill validation.jvm.fix
  ./mill validation.jvm.reformat
  ```
  Don't join them with `+`: Mill can then format the files before they are written.

Then commit the changed files.

Things to know:

- The metamodel and the conformance suite's model.yaml come from a copy of [NeverBlink-OSS/linkml-model](https://github.com/NeverBlink-OSS/linkml-model) that Mill keeps in `out/`, and Mill does not fetch it again on its own. To regenerate from the latest version, as CI does, run `./mill clean metamodel.definitionsRepo` first.
- `regenerate` runs the CLI, so the generated classes must compile. If they don't (for example, after a bad manual edit), revert them with `git checkout -- <module>/src` and regenerate again.
- CI runs `./mill regenerate` and fails if the result differs from what is committed in the model modules, `generator/npm`, `ohi` and `cli/ohi`.

#### Adding a model

1. Create a `package.mill` for the module, next to the module that uses the classes (like `generator/owlconfig/package.mill`).
2. Make the module extend `LinkMlModelModule`, set `modelGeneratorCli` to `build.cli.jvm.runner`, and set `modelSchema` and `modelPackage`. Set `otherModelOutputs` to also run other generators, like `typescript`.
3. Add the `jvm` module, and `js` and `native` if the classes are needed there, with a dependency on `build.runtime` (or `build.yaml`, if hand-written codecs live next to the classes).
4. Add the new module to the `moduleDeps` of the modules that use it.
5. Run `./mill regenerate` and commit the generated classes.

`./mill regenerate` and the CI check find the new module on their own. Hand-written code that needs more than the generated classes can stay in the module that uses it, in the same package.

### Scala Native

The library cross-compiles to Scala Native as well as the JVM and Scala.js, and all three are published to Maven Central. The shared library and the Python bindings are built with Scala Native too – see [docs/python_bindings.md](docs/python_bindings.md). The CLI executable is still a GraalVM native image.

The Scala Native submodules are **hidden from the build unless you enable them**, because the Scala Native toolchain rather heavy. To enable them, set `LINKML_NATIVE=1`:

```shell
LINKML_NATIVE=1 ./mill --no-server __.native.__.testForked   # all Scala Native tests
LINKML_NATIVE=1 ./mill --no-server schemaview.native.compile # just one module
LINKML_NATIVE=1 ./mill --no-server nativelib.native.pythonTest  # the C library and Python bindings
```

Building the shared library needs clang; the Scala modules only need the JVM.

The env var is set when starting the mill process, so if you have a daemon running, restart it and pass this variable.

### Playground UI

The browser playground lives in [`ui/`](ui/) – a TypeScript app (CodeMirror editors) bundled with [esbuild](https://esbuild.github.io/). It loads the Scala.js generator bundle at runtime. **Node.js and npm must be on your `PATH`** for the tasks below. The first run installs the npm dependencies automatically.

- Serve it locally: `./mill ui.serve` – builds the Scala.js bundle **and** the UI bundle, then serves at <http://localhost:8000/ui/>
- Build the UI bundle only: `./mill ui.bundle` → `ui/dist/app.js`
- Type-check and bundle (what CI runs): `./mill ui.check`
- Regenerate the LinkML API types: `./mill ui.types` – run after changing `generator/src-js/.../LinkMlJsApi.scala`

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
- Run a subset (regex over benchmark names): `./mill benchmark.runJmh ".*NTriples.*"`
- List available benchmarks: `./mill benchmark.listJmhBenchmarks`

## Releasing with GitHub UI

1. Go to Releases -> Draft a new release
2. Press "Select tag" list
3. Enter the tag name in form `v0.2.3`
4. Press "Generate Release Notes" button
5. Select "Pre-release" or "Release" list item
6. Press "Publish Release" button

## Snapshots

We publish snapshot versions of LinkML-Scala, you can find them [here](https://central.sonatype.com/repository/maven-snapshots/eu/neverblink/linkml/generator_3/maven-metadata.xml)
