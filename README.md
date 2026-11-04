<div align="center">

[![CLI release](https://img.shields.io/github/v/release/NeverBlink-OSS/ohi?style=flat-square&logo=github&logoColor=white&label=release&color=blue)](https://github.com/NeverBlink-OSS/ohi/releases/latest)
[![Discord](https://img.shields.io/badge/Discord-join_chat-5865F2?style=flat-square&logo=discord&logoColor=white)](https://discord.gg/HGksVAJ6ss)
[![GitHub stars](https://img.shields.io/github/stars/NeverBlink-OSS/ohi?style=flat-square&logo=github&logoColor=white&label=stars&color=yellow)](https://github.com/NeverBlink-OSS/ohi/stargazers)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue?style=flat-square&logo=apache&logoColor=white)](LICENSE)

</div>

# ohi

**ohi helps you find, download, and manage ontologies. ohi LinkML helps you write, check, and convert [LinkML](https://linkml.ohi.dev/) schemas.**
It's a single native command-line tool for Linux, macOS and Windows, with libraries for Python, JavaScript/TypeScript, C/C++/Rust, and the JVM.

| | What it does | |
|---|---|---|
| **ohi** | Search ontologies, download their files and manage ontology projects locally | [Docs](https://ohi.dev/docs/) · [Browse](https://ohi.dev) |
| **ohi LinkML** | Validate LinkML schemas and turn them into JSON Schema, SHACL, OWL, Pydantic and more | [Docs](https://linkml.ohi.dev/docs/) · [Playground](https://linkml.ohi.dev/playground/) |

> [!NOTE]
> ⭐ If you like ohi, consider giving it a star – it helps others find the project!

## Install

On Linux, macOS, or Windows with Git Bash, MSYS2, Cygwin or WSL:

```shell
. <(curl -sSfL https://ohi.dev/install.sh)
ohi version
```

In PowerShell:

```powershell
irm https://ohi.dev/install.ps1 | iex
ohi version
```

Read the scripts before running them: [install.sh](cli/install.sh), [install.ps1](cli/install.ps1).
For other ways to install (mise, manual download, the JAR) and how to verify a download, see
**[the install guide](https://ohi.dev/docs/getting-started/install/)**.

## Ontologies

Find an ontology on [ohi.dev](https://ohi.dev) and download it:

```shell
ohi search foaf
ohi get foaf/foaf@2014-01-14 --format owl-turtle
```

Or record the ontologies your project depends on, so anyone can download the same files later:

```shell
ohi init
ohi add w3c/prov-o@2013 --format owl-turtle
ohi sync   # on another machine, after cloning
```

`ohi.yaml` lists what you asked for, and `ohi.lock.json` lists the exact files and their checksums.
Commit both. See **[the ohi docs](https://ohi.dev/docs/)** for the
[first-ontology walkthrough](https://ohi.dev/docs/getting-started/first-ontology/),
[projects](https://ohi.dev/docs/guides/projects/) and the [full CLI reference](https://ohi.dev/docs/reference/cli/).

## LinkML

[LinkML](https://linkml.ohi.dev/) lets you describe your data once, in a simple YAML schema.
ohi LinkML checks the schema and generates other formats from it:

```shell
ohi linkml validate model.yaml
ohi linkml to json-schema model.yaml
ohi linkml to shacl model.yaml
ohi linkml from owl ontology.ttl   # turn an OWL ontology into LinkML
```

It tells you exactly what is wrong with your schema, and where:

![LinkML schema validation in the CLI](./website/src/assets/validate.gif)

- **Runs everywhere.** Native binaries, the JVM, Node.js, the browser (no install – try the [playground](https://linkml.ohi.dev/playground/)), Python, and C, C++ or Rust.
- **Fast.** [22.9–38.5x faster](https://linkml.ohi.dev/docs/benchmarks/) than LinkML-Python, even on very large schemas.
- **Consistent.** Every generator is rigorously tested against the same [set of LinkML models](https://github.com/NeverBlink-OSS/ohi/tree/main/tests/resources/models).

Run `ohi linkml --help` for the full list of generators and importers, or read
**[the LinkML docs](https://linkml.ohi.dev/docs/)**.

## Libraries

| Language | ohi LinkML | ohi |
|---|---|---|
| Python | [`neverblink-linkml`](https://linkml.ohi.dev/docs/python-bindings/) | [`ohi-sdk`](https://ohi.dev/docs/reference/sdks/) |
| JavaScript / TypeScript | [`@neverblink/linkml`](generator/npm/README.md) | [`@neverblink/ohi`](https://ohi.dev/docs/reference/sdks/) |
| JVM (Scala, Java, Kotlin) | [Maven Central](https://central.sonatype.com/namespace/eu.neverblink.linkml), [Javadoc](https://javadoc.io/doc/eu.neverblink.linkml/generator_3/latest/index.html) | – |
| C, C++, Rust | [Shared library](https://linkml.ohi.dev/docs/python-bindings/#how-it-works) from [Releases](https://github.com/NeverBlink-OSS/ohi/releases/latest) | – |

## Also available

- **GitHub Action** for validating and generating LinkML schemas in CI: [NeverBlink-OSS/linkml-scala-action](https://github.com/NeverBlink-OSS/linkml-scala-action).
- **Agent plugins** for Claude Code, Codex and other coding agents. See [the plugin guide](.agents/README.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build the project and send changes. Contributors need to sign
our [Contributor License Agreement](CLA.md). The project follows our [Code of Conduct](CODE_OF_CONDUCT.md).

Have a question or an idea? **[Join our Discord](https://discord.gg/HGksVAJ6ss)** – we're happy to help.

## License and maintainers

ohi is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

ohi is developed and maintained by [NeverBlink](https://neverblink.eu). For any inquiries, please
[email us](mailto:contact@neverblink.eu).

----

*This work has been supported by the HEDGE-IoT project grant number 101136216 funded by the European Commission as part of the Horizon Europe Framework Programme. However, views and opinions expressed are those of the authors only and do not necessarily reflect those of the European Union or the European Climate, Infrastructure and Environment Executive Agency. Neither the European Union nor the granting authority can be held responsible for them.*
