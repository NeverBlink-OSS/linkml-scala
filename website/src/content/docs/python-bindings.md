---
title: Python bindings
description: The Python package, and the C ABI for C, C++ and Rust.
---

ohi LinkML compiled to a native shared library, called from Python through `ctypes`. No JVM, no
subprocess per schema, and no dependency on the `linkml` Python package.

## Installation

```shell
pip install neverblink-linkml
```

The wheels on [PyPI](https://pypi.org/project/neverblink-linkml/) bundle the native library, so there is nothing else to install – no JVM, no compiler.

| OS            | Architectures        |
|---------------|----------------------|
| Linux (glibc) | x86-64, ARM64        |
| Linux (musl)  | x86-64, ARM64        |
| macOS         | Apple silicon, Intel |
| Windows       | x86-64, ARM64        |

We also provide source distributions (*sdist*), which should work on any platform that has Java 17+ and Clang. 

## Usage

```python
import linkml_scala

with linkml_scala.load_file("model.yaml") as schema:
    print(schema.json_schema())
    print(schema.shacl())
    for issue in schema.issues(linkml_scala.WARNING):
        print(issue["severity"], issue["message"])
```

A schema is parsed once and reused across generators, the same way the [JavaScript bindings](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/generator/npm/README.md) work. All generators are available, plus the schema validator.

## The API

### Loading

| Function | Use it when |
|---|---|
| `load_file(path)` | The schema and its imports are on disk. Behaves like the CLI. |
| `load_string(schema, imports=None)` | You have the schema as text. `imports` maps filename to YAML text. |
| `load_path(path, imports)` | Same, but the root is read *through* the map by its own path, so imports that reference the root back resolve to it instead of loading a second copy. |

Keys of the `imports` map are import paths as seen from the root schema. The `.yaml` and `.yml`
extensions are optional and interchangeable, and each imported schema is loaded only once, even
when it is reached through different relative paths or, with `load_file`, through symbolic or hard
links.

All three take `infer_messages=True`, which fills in each issue's human-readable `message` and
`details`. Turn it off for only the structured fields.

All three also take `import_map=`, which maps an import to the location to load it from, such as
`{"https://example.org/units/": "deps/units/"}`, or is the path of a JSON file with such an object.
For `load_file` a location is a path, and for `load_string` and `load_path` it is a key of the
`imports` map. See [Import maps](/docs/import-maps/).

Fatal problems raise `SchemaLoadError`, which carries the report as `.report`. Errors and warnings do
not: a schema can load and still have things to say about it.

A generator or importer that can't convert a schema or ontology, for a reason you can fix, raises
`SchemaConversionError`. Its `.issue` is a `SchemaConversionError` issue, in the same shape as the
issues of a report, and its `message` says why. For example, `schema.linkml()` raises it for a URI
that would be read back as a CURIE with another meaning, and `from_ossie` for a document that isn't
an Ossie ontology. Both exceptions are a `LinkMlError`, as is any other failure.

### Generating from LinkML

```python
schema.json_schema(open=False, tree_root=None, tree_root_inline_type=None, indentation_step=2)
schema.shacl(open=False, only_classes_from_root_schema=False, format="ttl")
schema.rdfs(only_classes_from_root_schema=False, format="ttl")
schema.linkml(pruning_mode="skip", tree_root=None, skip_class_derivation=False, output_format="yaml")
schema.frictionless(pruning_mode="skip", tree_root=None, skip_classes_without_identifier=False)
schema.graphql(pruning_mode="schema", tree_root=None)
schema.typescript(pruning_mode="skip", tree_root=None, include_null=False, open=False)
schema.pydantic(pruning_mode="skip", tree_root=None, include_null=False, open=False)
schema.er_diagram(pruning_mode="schema", tree_root=None, optional_marker=True)
schema.docs(pruning_mode="schema", tree_root=None, metadata_language="en", single_file=False)
schema.ossie(pruning_mode="skip", tree_root=None, output_format="yaml")
schema.owl(only_root_schema=False, metadata_profile="rdfs", permissible_values="individual", format="ttl")
schema.scala(package="eu.neverblink.linkml.metamodel", generate_emit_prefixes=True)
schema.translation(to="base")
```

All arguments are keyword-only. Every one returns a string, except `scala()`, `frictionless()` and
`docs()`, which return a filename-to-content dict.

`shacl()`, `rdfs()` and `owl()` take a `format`: `"ttl"` for Turtle, the default, which is prefixed and
pretty-printed, or `"nt"` for N-Triples.

### Converting to LinkML

We currently support importers for Apache Ossie (incubating) ontologies and for OWL ontologies:

```python
linkml_scala.from_ossie(ontology, schema_id=None, output_format="yaml")
linkml_scala.from_owl(ontology, config=None, schema_id=None, output_format="yaml", list_not_imported=False, imports=None, input_format="ttl")
```

Each returns the LinkML schema as a string. Pass that to `load_string()` to run a generator over it.
`from_owl()` reads Turtle (and so N-Triples), or N-Triples with its own parser with
`input_format="nt"`, and takes its mapping settings as YAML text in `config` (the text itself, not a path to a
file). With
`list_not_imported=True`, it lists what it could not import in comments at the top of the YAML.
`imports` gives the LinkML schemas the config maps `owl:imports` to, as for `load_string()`; by
default they are read from the file system. See [the OWL guide](/docs/owl/).

### Validating

`schema.report` is the report from loading. `schema.lint()` runs the validator again, which is only
worth doing to get a report with different settings. `schema.issues(severity)` filters by
`linkml_scala.FATAL`, `ERROR` or `WARNING`. Both follow the
[`validation-report.yaml`](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/model/validation-report.yaml) model, the same JSON the CLI's
`validate --format json` produces.

### Releasing

Use `Schema` as a context manager, or call `close()`. Dropping the last reference also releases it
via `__del__`, but only whenever the garbage collector gets round to it, so a `with` block is better
if you are loading many schemas.

## How fast is it?

Note: these are rough numbers on a Ryzen 7900 workstation. Full benchmark is still pending. See: [benchmark code](https://github.com/NeverBlink-OSS/linkml-scala/tree/main/benchmark/src), [datasets](https://github.com/NeverBlink-OSS/linkml-scala/tree/main/benchmark/resources/schemas).

Here we measured only the time to generate the output, not the time to load the schema. The Python side is LinkML 1.11.1, and the Scala side is ohi LinkML 975f65a, v0.13.1-15-g975f65a.

### JSON Schema

| Dataset          | Size   | linkml (Python) | linkml_scala |  Speedup |
|------------------|--------|----------------:|-------------:|---------:|
| `TC57CIM`        | 2.9 MB |        7,413 ms |      55.2 ms | **134×** |
| `cgmes-dynamics` | 812 KB |          952 ms |       5.5 ms | **174×** |
| `cgmes-core`     | 196 KB |          471 ms |       3.7 ms | **126×** |

### SHACL

| Dataset          | Size   | linkml (Python) | linkml_scala | Speedup |
|------------------|--------|----------------:|-------------:|--------:|
| `TC57CIM`        | 2.9 MB |       17,699 ms |       188 ms | **94×** |
| `cgmes-dynamics` | 812 KB |        2,039 ms |      20.8 ms | **98×** |
| `cgmes-core`     | 196 KB |        1,196 ms |      15.0 ms | **80×** |

## How it works

The library is compiled with [Scala Native](https://scala-native.org/), and exports one C function per operation:

```c
char* linkml_shacl      (long long handle, const char* opts, char** err);
char* linkml_json_schema(long long handle, const char* opts, char** err);
/* rdfs, owl, linkml, frictionless, graphql, typescript, pydantic, er_diagram, docs, scala, lint - same shape */

long long linkml_load_file(const char* path, const char* opts, char** report, char** err);
void      linkml_close    (long long handle);
void      linkml_free     (char*);

int       linkml_abi_version(void);
int       linkml_init_threads(void);
char*     linkml_build_info (char** err);
```

Conventions:

**Options are one JSON string, and may be NULL.** Options are the part that changes as generators grow, so keeping them out of the signatures keeps the ABI stable. NULL means "use defaults":

```c
char *shacl = linkml_shacl(handle, NULL, &err);               /* defaults */
char *open  = linkml_shacl(handle, "{\"open\":true}", &err);  /* one option */
```

The options of `linkml_load_file` and `linkml_load_string` take `inferMessages` and `importMap` (see [Import maps](/docs/import-maps/)):

```c
long long h = linkml_load_file("model.yaml",
    "{\"importMap\":{\"https://example.org/units/\":\"deps/units/\"}}", &report, &err);
```

**Failure is NULL plus a message.** A generator returns NULL and writes the reason to `*err`. Loading returns handle 0 and writes a validation report to `*report`. A schema that can't be written, or an ontology that can't be imported, writes a validation report to `*err` instead of a message, with one `SchemaConversionError` issue. All returned strings must be freed by the caller with `linkml_free`.

A loaded schema is an integer handle.

**One thread.** Due to a current limitation in Scala Native, the library has to be used from a single thread, which calls `linkml_init_threads()` once before anything else. The Python bindings do this for you: they own a worker thread and send all requests to it. Calling ohi LinkML from multiple threads in Python is safe, but the calls will be executed serially.

## Building it yourself

You need JDK 17+, clang, and Python 3.10 or newer. Then:

```shell
LINKML_NATIVE=1 ./mill nativelib.native.installPythonLib
```

Scala Native modules are hidden in the mill project unless `LINKML_NATIVE=1` – see [CONTRIBUTING](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/CONTRIBUTING.md#scala-native). That task links the library and copies it into `python/linkml_scala/_lib/`, where the Python package looks for it.

To use the package, put `python/` on your path:

```shell
PYTHONPATH=python python3 -c "import linkml_scala; print(linkml_scala.library_path())"
```

Run the tests with:

```shell
LINKML_NATIVE=1 ./mill nativelib.native.pythonTest
```

If you keep the library somewhere else, point `LINKML_SCALA_LIB` at the file (or at the directory
holding it).

### Building a wheel

```shell
pip install build
LINKML_NATIVE=1 ./mill nativelib.native.pythonWheel      # out/nativelib/native/pythonWheel.dest/dist/*.whl
LINKML_NATIVE=1 ./mill nativelib.native.pythonWheelTest  # installs it in a throwaway virtualenv
```

Releases include wheels for Linux, macOS and Windows on x86-64 and ARM64, glibc and musl.

### The source distribution

There is also a source distribution, for platforms that we don't have wheels for. The sdist contains Scala Native IR (NIR) and a portable linker. The linking and final compilation is done during `pip install` – this requires Java 17+ and Clang to be installed on your system. You can force pip to install from a wheel with `pip install --only-binary :all: neverblink-linkml`.

```shell
LINKML_NATIVE=1 ./mill nativelib.native.sdist      # out/nativelib/native/sdist.dest/*.tar.gz
LINKML_NATIVE=1 ./mill nativelib.native.sdistTest  # installs it in a virtualenv and runs the tests
```
