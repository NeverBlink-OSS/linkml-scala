# Python bindings (experimental)

LinkML-Scala compiled to a native shared library, called from Python through `ctypes`. No JVM, no
subprocess per schema, and no dependency on the `linkml` Python package.

> [!WARNING]
> This is an experiment.
> Feedback is welcome in [Discussions](https://github.com/NeverBlink-OSS/linkml-scala/discussions).

## Installation

```shell
pip install neverblink-linkml
```

The wheels on [PyPI](https://pypi.org/project/neverblink-linkml/) bundle the native library, so there is nothing else to install – no JVM, no compiler.

| OS            | Architectures        |
|---------------|----------------------|
| Linux (glibc) | x86-64, ARM64        |
| Linux (musl)  | x86-64               |
| macOS         | Apple silicon, Intel |
| Windows       | x86-64               |

Python 3.10 or newer, 64-bit only.

## Usage

```python
import linkml_scala

with linkml_scala.load_file("model.yaml") as schema:
    print(schema.json_schema())
    print(schema.shacl())
    for issue in schema.issues(linkml_scala.WARNING):
        print(issue["severity"], issue["message"])
```

A schema is parsed once and reused across generators, the same way the [JavaScript bindings](../generator/npm/README.md) work. All eight generators are available, plus the schema validator.

## The API

### Loading

| Function | Use it when |
|---|---|
| `load_file(path)` | The schema and its imports are on disk. Behaves like the CLI. |
| `load_string(schema, imports=None)` | You have the schema as text. `imports` maps filename to YAML text. |
| `load_path(path, imports)` | Same, but the root is read *through* the map by its own path, so imports that reference the root back resolve to it instead of loading a second copy. |

All three take `infer_messages=True`, which fills in each issue's human-readable `message` and
`details`. Turn it off for only the structured fields.

Fatal problems raise `SchemaLoadError`, which carries the report as `.report`. Errors and warnings do
not: a schema can load and still have things to say about it.

### Generating

```python
schema.json_schema(open=False, tree_root=None, tree_root_inline_type=None, indentation_step=2)
schema.shacl(open=False, only_classes_from_root_schema=False)
schema.rdfs(only_classes_from_root_schema=False)
schema.linkml(pruning_mode="skip", tree_root=None, skip_class_derivation=False, output_format="yaml")
schema.frictionless(pruning_mode="skip", tree_root=None, skip_classes_without_identifier=False)
schema.graphql(pruning_mode="schema", tree_root=None)
schema.er_diagram(pruning_mode="schema", tree_root=None, optional_marker=True)
schema.scala(package="eu.neverblink.linkml.metamodel", generate_emit_prefixes=True)
```

All arguments are keyword-only. Every one returns a string, except `scala()` and `frictionless()`,
which return a filename-to-content dict.

**These are generated, not written.** Each method mirrors the `Options` case class of the generator it calls – `ShaclGenerator.Options` and so on – so the names, types, defaults and docstrings are whatever the Scala declares. See [`mill-build/src/PyBindingsGen.scala`](../mill-build/src/PyBindingsGen.scala).

### Validating

`schema.report` is the report from loading. `schema.lint()` runs the validator again, which is only
worth doing to get a report with different settings. `schema.issues(severity)` filters by
`linkml_scala.FATAL`, `ERROR` or `WARNING`. Both follow the
[`validation-report.yaml`](../model/validation-report.yaml) model, the same JSON the CLI's
`validate --format json` produces.

### Releasing

Use `Schema` as a context manager, or call `close()`. Dropping the last reference also releases it
via `__del__`, but only whenever the garbage collector gets round to it, so a `with` block is better
if you are loading many schemas.

## How fast is it?

Note: these are rough numbers on a Ryzen 7900 workstation. Full benchmark is still pending. See: [benchmark code](../benchmark/src), [datasets](../benchmark/resources/schemas).

Here we measured only the time to generate the output, not the time to load the schema. The Python side is LinkML 1.11.1, and the Scala side is LinkML-Scala 975f65a, v0.13.1-15-g975f65a.

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

We export one function per operation:

```c
char* linkml_shacl      (graal_isolatethread_t*, long long handle, const char* opts, char** err);
char* linkml_json_schema(graal_isolatethread_t*, long long handle, const char* opts, char** err);
/* rdfs, linkml, frictionless, graphql, er_diagram, scala, lint – same shape */

long long linkml_load_file(graal_isolatethread_t*, const char* path,
                           const char* opts, char** report, char** err);
void      linkml_close    (graal_isolatethread_t*, long long handle);
void      linkml_free     (graal_isolatethread_t*, char*);

int       linkml_abi_version(graal_isolatethread_t*);
char*     linkml_build_info (graal_isolatethread_t*, char** err);
```

Conventions:

**Options are one JSON string, and may be NULL.** Options are the part that changes as generators grow, so keeping them out of the signatures keeps the ABI stable. NULL means "use defaults":

```c
char *shacl = linkml_shacl(thread, handle, NULL, &err);               /* defaults */
char *open  = linkml_shacl(thread, handle, "{\"open\":true}", &err);  /* one option */
```

**Failure is NULL plus a message.** A generator returns NULL and writes the reason to `*err`. Loading returns handle 0 and writes a validation report to `*report`. All returned strings must be freed by the caller with `linkml_free`.

A loaded schema is an integer handle.

## Building it yourself

You need JDK 17+ (Mill downloads GraalVM itself) and Python 3.10 or newer. Then:

```shell
./mill nativelib.installPythonLib
```

That runs `native-image --shared` over the `nativelib` module and copies the result into
`python/linkml_scala/_lib/`, where the Python package looks for it. Takes about half a minute on a
recent laptop, and produces a 22 MB `liblinkml_scala.so`.

To use the package, put `python/` on your path:

```shell
PYTHONPATH=python python3 -c "import linkml_scala; print(linkml_scala.library_path())"
```

Run the tests with:

```shell
./mill nativelib.pythonTest
```

If you keep the library somewhere else, point `LINKML_SCALA_LIB` at the file (or at the directory
holding it).

### Building a wheel

```shell
pip install build
./mill nativelib.pythonWheel      # writes out/nativelib/pythonWheel.dest/dist/*.whl
./mill nativelib.pythonWheelTest  # installs it in a throwaway virtualenv and runs the tests there
```

For Linux, we support both glibc and musl. The glibc build is done inside a manylinux2014 container, so it can be used on any Linux distribution. The musl build is done on the host, but requires a musl toolchain to be installed first – this works only on x86-64. This can be done with these scripts:

```shell
./mill nativelib.linux.glibc.pythonWheelTest  # glibc, built inside manylinux2014
sudo nativelib/install-musl-toolchain.sh      # once, for the musl build
./mill nativelib.linux.musl.pythonWheel       # musl, for Alpine
```

Each release also attaches a prebuilt archive per platform, `linkml-scala-lib-<os>-<arch>`, laid out
as a normal install prefix – `include/`, `lib/` and a pkg-config file – so C, C++ and Rust callers can
use the same library. [`nativelib/smoke.c`](../nativelib/smoke.c) is a worked example in C.
