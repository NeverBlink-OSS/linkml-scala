# Contributing to ohi LinkML

Issues and pull requests are welcome. If you plan something big, open an issue or ask on Discord first.

Questions and ideas: **[Discord](https://discord.gg/HGksVAJ6ss)**. The project follows our [Code of Conduct](CODE_OF_CONDUCT.md).

## Contributor License Agreement

Before we can merge your pull request, you need to sign our [Contributor License Agreement](CLA.md) (CLA)

You only sign once, and it covers all your later pull requests to this repository:

1. Open a pull request. The [CLA assistant](https://cla-assistant.io/) bot comments on it with a link.
2. Follow the link, sign in with GitHub, read the CLA and accept it.
3. The bot records your GitHub username and the time of signing, and the `license/cla` check on the pull request turns green. If it doesn't, use the "recheck" link in the bot's comment.

You can also sign before opening a pull request, at [cla-assistant.io/NeverBlink-OSS/ohi](https://cla-assistant.io/NeverBlink-OSS/ohi).

Every author of a commit in the pull request must sign, so make sure your commits are linked to your GitHub account (the commit email is one of your account's emails).

**Contributing for a company.** If you made the contribution as part of your job, your employer may own the copyright. In that case, your employer should sign the [CLA](CLA.md) and email it to [contact@neverblink.eu](mailto:contact@neverblink.eu) with the GitHub usernames of the people who will contribute. We'll then add them to the list of people who don't need to sign themselves.

## Setup

The build tool is [mill](https://mill-build.org/). The repository includes the `./mill` script, which downloads mill on first use.

| You need        | For                                                        |
|-----------------|------------------------------------------------------------|
| JDK 17 or newer | Everything                                                 |
| Node 24 and npm | The docs sites, playground and npm package.                |
| Python 3        | Tests of the Python bindings                               |

In IntelliJ, [import the project as BSP](https://mill-build.org/mill/cli/installation-ide.html#_intellij).

## Development commands

| Task                         | Command                                      |
|------------------------------|----------------------------------------------|
| Compile everything           | `./mill __.compile`                          |
| Test one module              | `./mill generator.jvm.test`                  |
| Test everything              | `./mill __.testForked`                       |
| Lint (scalafix and scalafmt) | `./mill lint`                                |
| Run the `linkml-scala` CLI   | `./mill cli.jvm.run --help`                  |
| Run the `ohi` CLI            | `./mill cli.ohi.jvm.run --help`              |
| Build a runnable jar         | `./mill cli.jvm.assembly`, `./mill cli.ohi.jvm.assembly` |
| Publish to the local Ivy repository | `./mill __.publishLocal`              |

## Docs and sites

| Site        | Sources                 | Serve locally           | Address                       |
|-------------|-------------------------|-------------------------|-------------------------------|
| [LinkML docs](https://linkml.ohi.dev/docs) | [`website/`](website/)  | `./mill website.serve`  | <http://localhost:4321/docs/> |
| [ohi docs](https://ohi.dev/docs)    | [`ohi/docs/`](ohi/docs/) | `./mill ohi.docs.serve` | <http://localhost:4321/docs/> |
| [LinkML site](https://linkml.ohi.dev) | [`ui/`](ui/), [`pages/`](pages/) | `./mill ui.serve` | <http://localhost:8000/>      |

The docs servers will reload on every change to the source files.

`ui.serve` serves the whole linkml.ohi.dev site as it is deployed: the landing page at `/`, the playground at `/playground/` and the docs at `/docs/`. It builds the docs once, so use `website.serve` to work on them.

The docs pages are Markdown files:

- LinkML docs: `website/src/content/docs/`. Add a new page to the sidebar in `website/astro.config.mjs` and to the list in `index.md`. Some pages are copied into the agent skills, so after editing a page, run `./mill agents.generate` (see [Generated files](#generated-files)).
- ohi docs: `ohi/docs/src/content/docs/`. Add a new page to the sidebar in `ohi/docs/astro.config.mjs`.

CI runs `./mill website.build`, `./mill ohi.docs.build` and `./mill ui.check`. The docs builds fail on broken links between pages and to headings. `website.preview` and `ohi.docs.preview` build a site and serve the result, with search.

The first run of any of these commands installs the npm dependencies.

## Generated files

Some source files are generated and committed. CI regenerates them and fails if the result differs from what is committed. After you change a schema, a generator or the metamodel version, regenerate and commit the result.

`./mill regenerate` updates everything except the last two rows:

| Files                                                  | Source                             |
|--------------------------------------------------------|------------------------------------|
| `metamodel`                                            | `linkml:meta`                      |
| `validation`, `generator/npm/validation-report.d.ts`   | `model/issue-types.yaml`           |
| `schemaview.buildinfo`, `generator/npm/build-info.d.ts` | `model/build-info.yaml`           |
| `generator.options`, `cli.options`                     | `model/converter/all_converters.yaml` |
| `generator/npm/generator-options.d.ts`, `generator/npm/importer-options.d.ts` | `model/converter/all_generators.yaml`, `model/converter/all_importers.yaml` |
| `tests.conformance`                                    | the conformance suite's model.yaml |
| ohi models in `ohi/` and `cli/ohi/`                    | `ohi/model/schema/`, see [ohi/README.md](ohi/README.md#regenerate-and-test) |
| Agent skill references in `.agents/skills/linkml/`     | the metamodel, four LinkML docs pages and `tests/resources/models/`, see [.agents/README.md](.agents/README.md#maintenance) |
| `ui/src/linkml.d.ts`                                   | `LinkMlJsApi.scala`, with `./mill ui.types` |
| `python/ohi_linkml/_generated.py`, `LinkMlCGenerators.scala` | `model/converter/all_converters.yaml`, with `LINKML_NATIVE=1 ./mill nativelib.bindings` |

The model classes are committed because generating them needs the CLI, which depends on them. So `regenerate` only works when the committed classes compile. If they don't, revert them with `git checkout -- <module>/src` and regenerate again.

The metamodel and the conformance suite come from a copy of [NeverBlink-OSS/linkml-model](https://github.com/NeverBlink-OSS/linkml-model) in `out/`. Mill does not update it on its own. To regenerate from the latest version, as CI does, run `./mill clean metamodel.definitionsRepo` first.

To regenerate one model, run these as separate commands:

```shell
./mill validation.regenerate
./mill validation.jvm.fix
./mill validation.jvm.reformat
```

If you join them with `+`, mill can format the files before they are written.

### Adding a model

1. Create a `package.mill` for the module, next to the module that uses the classes (like `generator/stats/package.mill`).
2. Make the module extend `LinkMlModelModule` (in `mill-build/src`), set `modelGeneratorCli` to `build.cli.jvm.runner`, and set `modelSchema` and `modelPackage`. To also run other generators, like `typescript`, set `otherModelOutputs`.
3. Add the `jvm` module, and `js` and `native` if the classes are needed there, with a dependency on `build.runtime` (or `build.yaml`, if hand-written codecs live next to the classes).
4. Add the new module to the `moduleDeps` of the modules that use it.
5. Run `./mill regenerate` and commit the generated classes.

`./mill regenerate` and the CI check find the new module on their own.

## Other targets

### Scala Native

The library is published for the JVM, Scala.js and Scala Native. The shared library and the [Python bindings](https://linkml.ohi.dev/docs/python-bindings/) are built with Scala Native.

The Scala Native modules are hidden unless `LINKML_NATIVE=1` is set, because the toolchain is slow to load. The variable is read when the mill daemon starts, so stop a running daemon first (`./mill shutdown`), or use `--no-server`:

```shell
LINKML_NATIVE=1 ./mill --no-server __.native.__.testForked      # all Scala Native tests
LINKML_NATIVE=1 ./mill --no-server schemaview.native.compile    # one module
LINKML_NATIVE=1 ./mill --no-server nativelib.native.pythonTest  # the shared library and the Python bindings
```

### Native CLI executables

The CLI executables are GraalVM native images. Mill downloads GraalVM.

```shell
./mill cli.jvm.nativeImage       # out/cli/jvm/nativeImage.dest/linkml-scala
./mill cli.ohi.jvm.nativeImage   # out/cli/ohi/jvm/nativeImage.dest/ohi
```

### npm package

```shell
./mill generator.js.npmPackage    # assemble the package
./mill generator.js.verifyPackage # run the README examples and compile the types
```

## Benchmarks

The JMH benchmarks are in [`benchmark/`](benchmark/).

```shell
./mill benchmark.runJmh                  # all benchmarks
./mill benchmark.runJmh ".*NTriples.*"   # those matching a regex
./mill benchmark.listJmhBenchmarks
```

## Releasing

Create a release in the GitHub UI with a new tag like `v0.2.3`, and press "Generate release notes". The tag starts [`release.yml`](.github/workflows/release.yml), which builds and publishes everything. A tag with a `-` in it, like `v0.3.0-rc1`, makes a pre-release and publishes the npm package under the `next` tag.

[Snapshot versions](https://central.sonatype.com/repository/maven-snapshots/eu/neverblink/linkml/generator_3/maven-metadata.xml) are published to Maven Central's snapshot repository.
