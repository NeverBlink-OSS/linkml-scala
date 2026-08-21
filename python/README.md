# neverblink-linkml

Fast and robust [LinkML](https://linkml.io/) schema validation and code generation for Python, backed by [LinkML-Scala](https://github.com/NeverBlink-OSS/linkml-scala) compiled to a native shared library.

No JVM, no subprocess per schema, and no dependency on the `linkml` Python package. In our
[benchmarks](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/docs/benchmarks.md) the generators are 22.9–38.5x faster than the reference Python implementation.

## Install

```shell
pip install neverblink-linkml
```

## Usage

```python
import linkml_scala

with linkml_scala.load_file("model.yaml") as schema:
    print(schema.json_schema())
    print(schema.shacl())
    for issue in schema.issues(linkml_scala.WARNING):
        print(issue["severity"], issue["message"])
```

`load_file()` will automatically resolve imports and parse the schema. You can reuse the `Schema` object for multiple generator calls, and it will automatically cache the parsed form of any imported schemas.

Generators available on a `Schema`: `json_schema()`, `shacl()`, `rdfs()`, `linkml()`,
`table_schema()`, `graphql()`, `er_diagram()` and `scala()`, plus `lint()` for validation.

To work from memory instead of the file system, use `load_string()` for a single schema or
`load_path()` when imports are involved:

```python
schema = linkml_scala.load_path("model.yaml", {
    "model.yaml": "...",
    "person.yaml": "...",
})
```

## Supported platforms

| OS            | Architectures        |
|---------------|----------------------|
| Linux (glibc) | x86-64, ARM64        |
| Linux (musl)  | x86-64, ARM64        |
| macOS         | Apple silicon, Intel |
| Windows       | x86-64               |

Python 3.10 or newer, 64-bit only. Other platforms are currently not supported, and would require an alternative compilation path (e.g., Scala Native). Please [open an issue](https://github.com/NeverBlink-OSS/linkml-scala/issues/new) if you need a platform that is not listed above.

## Documentation

- [Python bindings guide](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/docs/python_bindings.md)
  – all the options, and how the C ABI underneath works
- [LinkML-Scala README](https://github.com/NeverBlink-OSS/linkml-scala) – the CLI, the JVM library
  and the JavaScript library
- [Online playground](https://linkml.neverblink.eu/playground/)

## License

Apache 2.0.
