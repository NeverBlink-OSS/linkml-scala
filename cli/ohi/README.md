# ohi CLI

The `ohi` command-line tool finds ontology publications, downloads their files, tracks the
publications a project depends on, and runs the [LinkML commands](https://linkml.ohi.dev/docs/)
under `ohi linkml`.

**The documentation is at [ohi.dev/docs](https://ohi.dev/docs/).**

## Install

```sh
. <(curl -sSfL https://ohi.dev/install.sh)   # Bash or Zsh
irm https://ohi.dev/install.ps1 | iex        # PowerShell
```

See [Install ohi](https://ohi.dev/docs/getting-started/install/) for other ways to install and the
installer's settings.

## Quick start

```sh
ohi search provenance
ohi get w3c/prov-o@2013 --format owl-turtle
ohi linkml validate model.yaml
```

Next steps:

- [Download your first ontology](https://ohi.dev/docs/getting-started/first-ontology/)
- [Manage ontology projects](https://ohi.dev/docs/guides/projects/) with `ohi.yaml` and `ohi.lock.json`
- [CLI reference](https://ohi.dev/docs/reference/cli/)

## Building from source

See [CONTRIBUTING.md](../../CONTRIBUTING.md#native-cli-executables).
