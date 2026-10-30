---
title: ohi LinkML documentation
description: Guides for ohi LinkML, a LinkML implementation for the JVM, browsers, Python and native code.
---

[ohi LinkML](https://github.com/NeverBlink-OSS/linkml-scala) is a cross-platform implementation of [LinkML](https://linkml.io/).
It validates LinkML schemas and generates JSON Schema, SHACL, OWL, Pydantic, TypeScript and more from them.
It runs as a native command-line tool, on the JVM, in Node.js and the browser, and from Python, C, C++ and Rust.

To try it without installing anything, open the [playground](https://linkml.ohi.dev/playground/).
To install the CLI or the libraries, see the [README](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/README.md#cli-tool-installation).

## Generators and importers

- [LinkML <-> OWL](/docs/owl/): the OWL generator and the OWL importer.
- [Pydantic](/docs/pydantic/): Python classes for your data.
- [TypeScript](/docs/typescript/): TypeScript types for your data.
- [LinkML <-> Apache Ossie](/docs/ossie-mapping/): how the Ossie generator and importer map between the two.

## Schemas

- [URIs and CURIEs](/docs/curies/): how prefixes and CURIEs are resolved.
- [Import maps](/docs/import-maps/): loading imported schemas from somewhere else.
- [Inherited slots and `slot_usage`](/docs/slot-usage-inheritance/): how metaslots from several places are combined.

## Using ohi LinkML

- [Python bindings](/docs/python-bindings/): the Python package, and the C ABI for C, C++ and Rust.
- [Verifying downloads](/docs/verifying-downloads/): checking checksums and build provenance of releases.

## Reference

- [Implementation differences](/docs/implementation-differences/): where ohi LinkML differs from LinkML-Python.
- [Benchmarks](/docs/benchmarks/): how fast ohi LinkML is compared to LinkML-Python.
