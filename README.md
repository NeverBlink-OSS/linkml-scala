# LinkML-Scala
[![Maven Central Version](https://img.shields.io/maven-central/v/eu.neverblink.linkml/generator_3)](https://central.sonatype.com/namespace/eu.neverblink.linkml) [![Maven metadata URL](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fcentral.sonatype.com%2Frepository%2Fmaven-snapshots%2Feu%2Fneverblink%2Flinkml%2Fgenerator_3%2Fmaven-metadata.xml)](https://central.sonatype.com/repository/maven-snapshots/eu/neverblink/linkml/generator_3/maven-metadata.xml)


LinkML-Scala is a robust, cross-platform library (JVM, Scala.JS, Scala Native) powered by Scala 3.8+.

Prerequisite Note: LinkML-Scala requires JDK 17 or higher. It will not run on older Java versions.

While primarily a library, you can access its core features (like schema validation and code generation) 
directly from your terminal using the `linkml-scala` Command-Line Interface (CLI) tool.

## 🚀 Quick Start: Installation

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
— a single self-contained ES module (with TypeScript declarations) compiled from Scala via Scala.js,
with no runtime dependencies.

```shell
npm install @neverblink/linkml
```

```js
import { LinkML } from "@neverblink/linkml";

// The second argument is an import map (filename -> YAML) for `imports:`; {} if none.
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
