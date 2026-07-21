<div align="center">

[![Playground](https://img.shields.io/badge/playground-try_it_live-8A2BE2?style=flat-square&logo=scala&logoColor=white)](https://linkml.neverblink.eu/playground)
[![GitHub stars](https://img.shields.io/github/stars/NeverBlink-OSS/linkml-scala?style=flat-square&logo=github&logoColor=white&label=stars&color=yellow)](https://github.com/NeverBlink-OSS/linkml-scala/stargazers)
[![Maven Central](https://img.shields.io/maven-central/v/eu.neverblink.linkml/generator_3?style=flat-square&logo=apachemaven&logoColor=white&label=maven%20central)](https://central.sonatype.com/namespace/eu.neverblink.linkml)
[![npm](https://img.shields.io/npm/v/@neverblink/linkml?style=flat-square&logo=npm&logoColor=white&label=npm)](https://www.npmjs.com/package/@neverblink/linkml)
[![CLI release](https://img.shields.io/github/v/release/NeverBlink-OSS/linkml-scala?style=flat-square&logo=github&logoColor=white&label=CLI%20release&color=blue)](https://github.com/NeverBlink-OSS/linkml-scala/releases/latest)

[![Platforms](https://img.shields.io/badge/platforms-JVM_·_JS_·_native-4B8BBE?style=flat-square)](#-quick-start-installation)
[![Scala 3](https://img.shields.io/badge/Scala-3.8-DC322F?style=flat-square&logo=scala&logoColor=white)](https://www.scala-lang.org)
[![Scala.js](https://img.shields.io/badge/Scala.js-1.22-blue?style=flat-square&logo=scala&logoColor=white)](https://www.scala-js.org)
[![JDK 17+](https://img.shields.io/badge/JDK-17+-f89820?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue?style=flat-square&logo=apache&logoColor=white)](LICENSE)

</div>

# LinkML-Scala

**[LinkML](https://linkml.io/) is an open framework that simplifies authoring, validating, and sharing data.** You write your data model once in a simple YAML format. LinkML then generates code, schemas, and validation rules for multiple programming languages and data formats (e.g., JSON Schema, CSV, RDF/SHACL...).

**LinkML-Scala** is a robust, cross-platform implementation of LinkML. It works in the [JVM](#jvm-library), [in your browser](https://linkml.neverblink.eu/playground/) or [Node.js](#javascript--typescript-library), and even [compiles to native binaries](#-natively-compiled-binaries-for-linux-macos-and-windows). We have a command-line interface (CLI), a library for programmatic access, and a [GitHub Action](https://github.com/NeverBlink-OSS/linkml-scala-action).

## Why LinkML-Scala?

### 🚀 It's really fast!

LinkML-Scala was built to work great with large schemas. It can be **10–20x faster than the Python implementation**:

![LinkML-Scala vs LinkML-Python: generating a SHACL file](./docs/img/generate-race.gif)

We are right now working on a benchmark suite to compare LinkML-Scala with the Python implementation. Stay tuned for updates!

### 🌐 Works in the browser, Node.js, and in the JVM

LinkML-Scala compiles to pure JavaScript (zero dependencies, no Wasm), so you can use it in your browser or Node.js environment. Try it out in our **[online playground](https://linkml.neverblink.eu/playground/)**, or **[get it from npm](https://www.npmjs.com/package/@neverblink/linkml)**.

For Java, Scala, Kotlin and other JVM languages, LinkML-Scala is available on **[Maven Central](https://central.sonatype.com/namespace/eu.neverblink.linkml)**.

[![Web playground screenshot](./docs/img/playground.png)](https://linkml.neverblink.eu/playground)

### ⚡ Natively compiled binaries for Linux, macOS, and Windows

`linkml-scala` is a self-contained binary that runs natively on Linux, macOS, and Windows. You don't need Python, Java, or anything else installed.

It starts up immediately and runs fast, even on extra-large LinkML models. **[See the installation instructions.](#cli-tool-installation)**

It's also possible to compile LinkML-Scala to a native shared library for use in Rust, C, C++, and other languages. [Let us know](https://github.com/NeverBlink-OSS/linkml-scala/discussions) if you would like to see this feature!

### 🛡️ Consistent, reliable, and with great error reporting

LinkML-Scala will tell you exactly what is wrong with your model, and where:

![linkml-scala validate CLI command](./docs/img/validate.gif)

We engineered it to be as consistent as possible, so you are much less likely to run into "fun surprises" when using it with different generators. We test each generator across the same extensive [suite of LinkML models](https://github.com/NeverBlink-OSS/linkml-scala/tree/main/tests/resources/models).

> [!NOTE]
> ⭐ If you like LinkML-Scala, consider giving it a star – it helps others find the project!

## CLI tool installation

### Method 1: Install script (recommended for Unix/macOS)

If you are on Linux (x86-64, ARM64), macOS (ARM64), or using WSL on Windows, the easiest way to grab the latest release is via our installation script:

```shell
. <(curl -sSfL https://raw.githubusercontent.com/NeverBlink-OSS/linkml-scala/refs/heads/main/cli/install.sh)
linkml-scala
```

### Method 2: Using [mise](https://mise.jdx.dev/getting-started.html) (cross-platform)

You can install `linkml-scala` on any platform (including Windows) using the [mise](https://mise.jdx.dev/getting-started.html)
environment manager:

```shell
mise use 'github:NeverBlink-OSS/linkml-scala'
linkml-scala
```

Or install a specific version (useful if a new release is within mise's 7-day registry caching window)
```shell
mise use 'github:NeverBlink-OSS/linkml-scala@v0.8.6'
linkml-scala
```

### Method 3: Manual download

If you prefer a manual setup, head over to [Releases](https://github.com/NeverBlink-OSS/linkml-scala/releases/tag/v0.8.9) and download the pre-compiled binary for your specific OS and architecture.

*For macOS and Linux:*
Rename the downloaded file, make it executable, and run it:

```shell
mv linkml-scala-<os>-<arch> linkml-scala
chmod +x linkml-scala
./linkml-scala
```

*For Windows:*
Simply rename the downloaded executable, and run it:

```shell
ren linkml-scala-windows-x86_64.exe linkml-scala.exe
linkml-scala.exe
```

### CLI – getting started

#### LinkML schema validation

The `validate` command inspects your LinkML schema for structural and logical issues:

```shell
linkml-scala validate <input-file>
```

The CLI outputs issues directly to your terminal, categorized into three severity levels:
- Fatal: critical blockers (unknown references, invalid range types, used undefined default range)
- Errors: structural violations (multiple keys or ID slots, multiple tree roots, ID collisions)
- Warnings: non-critical issues (invalid slot usage, undefined default range, missing tree root)

All generators require that there are no *fatal* issues in the schema. 
The process will report these and exit immediately, as generation cannot proceed.

#### Generators

Generate a standard JSON Schema from your model:

```shell
linkml-scala generate json-schema <input-file>
```

Generate SHACL (Shapes Constraint Language) graphs for RDF validation:

```shell
linkml-scala generate shacl --to <output-path> <input-file>
```

**There's more!** Run `linkml-scala --help` to see the full list of supported generators. You can also run `linkml-scala generate shacl --help` to see the options for any specific generator.

#### LinkML schema derivation and pruning 

Generate a LinkML model that:
- Has all imports resolved
- Has all class slots materialized into attributes (opt out by passing `--skip-derivation`)
- Has all unused elements removed (controlled by `--pruning-mode`)

```shell
./linkml-scala generate linkml --to <output-path> <input-file>
```

## JavaScript / TypeScript library

The generator is also published to npm as [`@neverblink/linkml`](https://www.npmjs.com/package/@neverblink/linkml) – a single self-contained ES module (with TypeScript declarations) compiled from Scala via Scala.js, with no runtime dependencies.

```shell
npm install @neverblink/linkml
```

```js
import { LinkML } from "@neverblink/linkml";

// Parse once into a reusable SchemaView handle. The second argument is an
// import map (filename -> YAML) for `imports:`; pass {} when there are none.
const view = LinkML.loadFromString(mySchemaYaml, {});
const jsonSchema = LinkML.jsonSchema(view);
```

Load a schema with `loadFromString` (from YAML text) or `loadFromPath` (from a path in the
import map, immune to cyclic imports involving the root), then run `jsonSchema`, `shacl`,
`rdfs`, `linkml`, `scala`, `tableSchema`, or `lint` against the returned handle.
See [generator/npm/README.md](generator/npm/README.md) for details.

## JVM library

All modules are published to Maven Central under the `eu.neverblink.linkml` group ID. Best way to get started is to either **[browse the Javadoc](https://javadoc.io/doc/eu.neverblink.linkml/generator_3/latest/index.html)** or [read the CLI's source code](https://github.com/NeverBlink-OSS/linkml-scala/tree/main/cli/src/eu/neverblink/linkml/cli).

## GitHub Action (CI)

We have a pre-packaged GitHub Action that can do schema validation and generation in your CI pipelines. See **[NeverBlink-OSS/linkml-scala-action](https://github.com/NeverBlink-OSS/linkml-scala-action)** for more details.

## Contributing

This project is governed by our [Code of Conduct](CODE_OF_CONDUCT.md), adapted from the Mozilla Community Participation Guidelines.

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, common mill tasks, and how to contribute.

## License and maintainers

LinkML-Scala is licensed under the Apache License 2.0. See the LICENSE file for details.

This project is being developed and maintained by [NeverBlink](https://neverblink.eu). For any inquiries, please reach out to us via [email](mailto:contact@neverblink.eu).
