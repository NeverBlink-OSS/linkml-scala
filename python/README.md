# neverblink-linkml

Fast and robust [LinkML](https://linkml.io/) schema validation and code generation for Python, backed by [ohi LinkML](https://github.com/NeverBlink-OSS/ohi) compiled to a native shared library.

## Install

```shell
pip install neverblink-linkml
```

## Usage

```python
import ohi_linkml

with ohi_linkml.load_file("model.yaml") as schema:
    print(schema.json_schema())
    print(schema.shacl())
    for issue in schema.issues(ohi_linkml.WARNING):
        print(issue["severity"], issue["message"])
```

`load_file()` will automatically resolve imports and parse the schema. You can reuse the `Schema` object for multiple generator calls, and it will automatically cache the parsed form of any imported schemas.

Generators available on a `Schema`: `json_schema()`, `shacl()`, `rdfs()`, `linkml()`,
`frictionless()`, `graphql()`, `typescript()`, `pydantic()`, `er_diagram()`, `docs()`, `translation()`,
`ossie()`, `owl()`, `scala()` and `stats()`, plus `lint()` for validation. For how to use the classes
`pydantic()` generates, see
[the Pydantic guide](https://linkml.ohi.dev/docs/generators/pydantic/).

Going the other way, `ohi_linkml.from_ossie()` reads an Apache Ossie (incubating) ontology, and `ohi_linkml.from_owl()` an OWL ontology in Turtle or N-Triples. Each returns a dict with the corresponding LinkML schema, and warnings about what it could not import.

To work from memory instead of the file system, use `load_string()` for a single schema or
`load_path()` when imports are involved:

```python
schema = ohi_linkml.load_path("model.yaml", {
    "model.yaml": "...",
    "person.yaml": "...",  # referenced via `imports: - person`
})
```

To load an import from another location without changing the schema, pass `import_map=`, a
mapping or the path of a JSON file:

```python
schema = ohi_linkml.load_file(
    "model.yaml",
    import_map={"https://example.org/units/": "deps/units/"},
)
schema = ohi_linkml.load_file("model.yaml", import_map="imports.json")
```

See [Import maps](https://linkml.ohi.dev/docs/import-maps/).

## Supported platforms

We provide both sdist (source distribution) and pre-built wheels for popular platforms (Linux, macOS, Windows, x86-64 and ARM64).

To build the package from source (sdist) you must have Java 17+ and Clang installed on your system. Please [open an issue](https://github.com/NeverBlink-OSS/ohi/issues/new) if you encounter any issues with the installation process.

## Documentation

- [Python bindings guide](https://linkml.ohi.dev/docs/python-bindings/)
  – all the options, and how the C ABI underneath works
- [ohi LinkML README](https://github.com/NeverBlink-OSS/ohi) – the CLI, the JVM library
  and the JavaScript library
- [Online playground](https://linkml.ohi.dev/playground/)

## License

Apache 2.0.
