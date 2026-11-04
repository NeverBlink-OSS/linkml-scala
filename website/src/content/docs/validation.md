---
title: Validating schemas
description: Check LinkML schemas for problems, and read the validation report.
---

The `validate` command (also available as `lint`) checks LinkML schemas for structural and logical problems:

```shell
ohi linkml validate model.yaml
```

You can pass several schemas at once. Each one is checked, even if an earlier one has problems.

## Severity levels

Each problem has one of three severity levels:

- **Fatal**: the schema can't be loaded. For example: unknown references, invalid range types, an undefined default range that is used, or invalid URIs or CURIEs.
- **Error**: the schema loads, but breaks a structural rule. For example: several key or identifier slots in one class, several tree roots, or colliding identifiers.
- **Warning**: the schema works, but something is probably wrong. For example: invalid slot usage, an undefined default range, no tree root, undeclared CURIE prefixes, or missing recommended slots such as `description`.

The generators refuse to run on a schema with *fatal* problems. Errors and warnings don't stop them.

## Exit code

`validate` exits with code 1 if any schema has a fatal problem or an error, and with 0 otherwise.
Add `--strict` to also fail on warnings, for example in CI.

## Output formats

Choose the format of the report with `--format`:

- `terminal` (default): colored and grouped, for reading in a terminal.
- `plain`: the same text without colors, for logs and other tools.
- `json`: a machine-readable report, useful for editors, web UIs and scripts.

The JSON report follows the [validation report schema](https://github.com/NeverBlink-OSS/ohi/blob/main/model/validation-report.yaml).

## Validating from code

The libraries return the same problems:

- Python: `schema.report`, or `schema.issues(severity)` to filter it. See [Python bindings](/docs/python-bindings/#validating).
- JavaScript: the `report` returned by `LinkML.loadFromString`, or `LinkML.lint(view)`. See the [npm package README](https://github.com/NeverBlink-OSS/ohi/blob/main/generator/npm/README.md).

Both give the same report as `--format json`.

To load imported schemas from somewhere else while validating, see [Import maps](/docs/import-maps/).
