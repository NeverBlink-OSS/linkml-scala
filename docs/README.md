# LinkML-Scala documentation

[LinkML-Scala](https://github.com/NeverBlink-OSS/linkml-scala) is a cross-platform implementation of [LinkML](https://linkml.io/).
It validates LinkML schemas and generates JSON Schema, SHACL, OWL, Pydantic, TypeScript and more from them.
It runs as a native command-line tool, on the JVM, in Node.js and the browser, and from Python, C, C++ and Rust.

To try it without installing anything, open the [playground](https://linkml.ohi.dev/playground/).
To install the CLI or the libraries, see the [README](../README.md#cli-tool-installation).

## Generators and importers

- [LinkML <-> OWL](owl.md): the OWL generator and the OWL importer.
- [Pydantic](pydantic.md): Python classes for your data.
- [TypeScript](typescript.md): TypeScript types for your data.
- [LinkML <-> Apache Ossie](ossie_mapping.md): how the Ossie generator and importer map between the two.

## Schemas

- [URIs and CURIEs](curies.md): how prefixes and CURIEs are resolved.
- [Import maps](import_maps.md): loading imported schemas from somewhere else.
- [Inherited slots and `slot_usage`](slot_usage_inheritance.md): how metaslots from several places are combined.

## Using LinkML-Scala

- [Python bindings](python_bindings.md): the Python package, and the C ABI for C, C++ and Rust.
- [Verifying downloads](verifying_downloads.md): checking checksums and build provenance of releases.

## Reference

- [Implementation differences](implementation_differences.md): where LinkML-Scala differs from LinkML-Python.
- [Benchmarks](benchmarks.md): how fast LinkML-Scala is compared to LinkML-Python.
