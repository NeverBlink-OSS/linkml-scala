<div align="center">

[![Playground](https://img.shields.io/badge/playground-try_it_live-8A2BE2?style=flat-square&logo=scala&logoColor=white)](https://linkml.neverblink.eu/playground)
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

**[LinkML](https://linkml.io/) is an open framework that simplifies authoring, validating, and sharding data.** You write your data model once in a simple YAML format, and LinkML generates code, schemas, and validation rules for multiple programming languages and data formats.

**LinkML-Scala** is a robust, cross-platform implementation of LinkML. It works in the JVM, [in your browser](https://linkml.neverblink.eu/playground/) or Node.js (pure JavaScript!), and even compiles to native binaries. We have both a command-line interface (CLI) and a library for programmatic access.

## Why LinkML-Scala?

### 🚀 It's really fast!

TODO

### 🌐 Works in the browser and Node.js

### ⚡ Natively compiled binaries for Linux, macOS, and Windows

### 🧭 Actually useful error messages

TODO



library (JVM, Scala.JS) powered by Scala 3.8+.

Prerequisite Note: LinkML-Scala requires JDK 17 or higher. It will not run on older Java versions.

While primarily a library, you can access its core features (like schema validation and code generation) 
directly from your terminal using the `linkml-scala` Command-Line Interface (CLI) tool.

## Quick start: Installation

Choose the installation method that best fits your operating system and workflow. Once installed, running `linkml-scala`
without any options will print a helpful list of available commands.

### Method 1: The Install Script (Recommended for Unix/macOS)

If you are on Linux (x86_64, ARM64), macOS (ARM64), or using WSL on Windows, the easiest way to grab the latest release 
is via our installation script:

```shell
. <(curl -sSfL https://raw.githubusercontent.com/NeverBlink-OSS/linkml-scala/refs/heads/main/cli/install.sh)
linkml-scala
```

### Method 2: Using [mise](https://mise.jdx.dev/getting-started.html) (Cross-Platform)

You can install `linkml-scala` on any platform (including Windows) using [mise](https://mise.jdx.dev/getting-started.html)
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

### Method 3: Manual Download

If you prefer a manual setup, head over to the Releases Page and download the pre-compiled binary for your specific OS 
and architecture.

*For macOS and Linux:*
Rename the downloaded file, make it executable, and run it:

```shell
mv linkml-scala-<os>-<arch> linkml-scala
./linkml-scala
```

*For Windows:*
Simply rename the downloaded executable, and run it:

```shell
ren linkml-scala-windows-x86_64.exe linkml-scala.exe
linkml-scala.exe
```

### CLI Usage Guide

#### LinkML schema validation

The `validate` command inspects your LinkML schema for structural and logical issues:

```shell
./linkml-scala validate <input-file>
```

The CLI outputs issues directly to your terminal, categorized into three severity levels:
- Fatal: critical blockers (unknown references, invalid range types, used undefined default range)
- Errors: structural violations (multiple keys or ID slots, multiple tree roots, ID collisions)
- Warnings: non-critical issues (invalid slot usage, undefined default range, missing tree root)

Fatal issues are critical for generation. 
The process will report these and exit immediately, as generation cannot proceed.

#### JSON Schema generation

Generate a standard JSON Schema from your model:

```shell
./linkml-scala generate json-schema <input-file>
```

#### Generation of Scala classes

Generate Scala data structures from your model:
```shell
./linkml-scala generate scala --package <scala-package> --to <output-path> <input-file>
```
Parameters:
- `<scala-package>` - package name for generated classes, _default value: eu.neverblink.linkml.metamodel_
- `<output-path>` - destination file or directory, _if not specified, output will be written to stdout_

#### Generation of SHACL shapes

Generate SHACL (Shapes Constraint Language) graphs for RDF validation:
```shell
./linkml-scala generate shacl --to <output-path> <input-file>
```
Parameters:
- `<output-path>` - destination file or directory, _if not specified, output will be written to stdout_

#### Generation of RDF schema

Generate RDF schema:
```shell
./linkml-scala generate rdfs --to <output-path> <input-file>
```
Parameters:
- `<output-path>` - destination file or directory, _if not specified, output will be written to stdout_

#### LinkML schema derivation and pruning 

Generate a LinkML model that:
- Has all imports resolved
- Has all class slots materialized into attributes (opt out by passing `--skip-derivation`)
- Has all unused elements removed (controlled by `--pruning-mode`)

```shell
./linkml-scala generate linkml --to <output-path> <input-file>
```

## Playground

Try LinkML-Scala live in your browser at [linkml.neverblink.eu/playground](https://linkml.neverblink.eu/playground), or run it locally with `./mill ui`.

## JavaScript / TypeScript library

The generator is also published to npm as [`@neverblink/linkml`](https://www.npmjs.com/package/@neverblink/linkml)
– a single self-contained ES module (with TypeScript declarations) compiled from Scala via Scala.js,
with no runtime dependencies.

```shell
npm install @neverblink/linkml
```

```js
import { LinkML } from "@neverblink/linkml";

// The second argument is an import map (filename -> YAML) for `imports:`.
const jsonSchema = LinkML.jsonSchema(mySchemaYaml, {});
```

`LinkML` exposes `jsonSchema`, `shacl`, `rdfs`, `linkml`, `scala`, `tableSchema`, and `lint`.
See [generator/npm/README.md](generator/npm/README.md) for details.

## Contributing

This project is governed by our [Code of Conduct](CODE_OF_CONDUCT.md), adapted from the Mozilla Community Participation Guidelines.

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, common mill tasks, and how to contribute.

## License and maintainers

LinkML-Scala is licensed under the Apache License 2.0. See the LICENSE file for details.

This project is being developed and maintained by [NeverBlink](https://neverblink.eu). For any inquiries, please reach out to us via [email](mailto:contact@neverblink.eu).
