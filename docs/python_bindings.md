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
schema.shacl(open=False, only_classes_from_root_schema=False, format="ttl")
schema.rdfs(only_classes_from_root_schema=False, format="ttl")
schema.linkml(pruning_mode="skip", tree_root=None, skip_class_derivation=False, output_format="yaml")
schema.frictionless(pruning_mode="skip", tree_root=None, skip_classes_without_identifier=False)
schema.graphql(pruning_mode="schema", tree_root=None)
schema.er_diagram(pruning_mode="schema", tree_root=None, optional_marker=True)
schema.scala(package="eu.neverblink.linkml.metamodel", generate_emit_prefixes=True)
```

All arguments are keyword-only. Every one returns a string, except `scala()` and `frictionless()`,
which return a filename-to-content dict.

`shacl()` and `rdfs()` take a `format`: `"ttl"` for Turtle, the default, which is prefixed and
pretty-printed, or `"nt"` for N-Triples.

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

The library is compiled with [Scala Native](https://scala-native.org/), and exports one C function
per operation:

```c
char* linkml_shacl      (long long handle, const char* opts, char** err);
char* linkml_json_schema(long long handle, const char* opts, char** err);
/* rdfs, linkml, frictionless, graphql, er_diagram, scala, lint - same shape */

long long linkml_load_file(const char* path, const char* opts, char** report, char** err);
void      linkml_close    (long long handle);
void      linkml_free     (char*);

int       linkml_abi_version(void);
int       linkml_init_threads(void);
char*     linkml_build_info (char** err);
```

Conventions:

**Options are one JSON string, and may be NULL.** Options are the part that changes as generators
grow, so keeping them out of the signatures keeps the ABI stable. NULL means "use defaults":

```c
char *shacl = linkml_shacl(handle, NULL, &err);               /* defaults */
char *open  = linkml_shacl(handle, "{\"open\":true}", &err);  /* one option */
```

**Failure is NULL plus a message.** A generator returns NULL and writes the reason to `*err`.
Loading returns handle 0 and writes a validation report to `*report`. All returned strings must be
freed by the caller with `linkml_free`.

A loaded schema is an integer handle.

**One thread.** Scala Native cannot register a thread it did not create
([scala-native#4951](https://github.com/scala-native/scala-native/issues/4951)), so the library has
to be used from a single thread, which calls `linkml_init_threads()` once before anything else. The
Python bindings do this for you: they own a worker thread and hand every call to it, so calling from
several Python threads is safe, just not parallel.

## Building it yourself

You need JDK 17+, clang, and Python 3.10 or newer. Then:

```shell
LINKML_NATIVE=1 ./mill nativelib.native.installPythonLib
```

Scala Native modules are hidden unless `LINKML_NATIVE=1`, because the toolchain is slow — see
[CONTRIBUTING](../CONTRIBUTING.md#scala-native). That task links the library and copies it into
`python/linkml_scala/_lib/`, where the Python package looks for it. Takes about 20 seconds and
produces an 11 MB `liblinkml_scala.so`.

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

The build is `release-fast` with the Immix collector and no LTO. `release-full` is 4.5% faster for
+42% library size, double the start-up and a much slower link, and LTO breaks exception handling in
a library build, so neither is on. `LINKML_SCALA_MODE`, `LINKML_SCALA_GC` and `LINKML_SCALA_LTO`
override those when building from a source distribution.

### Building a wheel

```shell
pip install build
LINKML_NATIVE=1 ./mill nativelib.native.pythonWheel      # out/nativelib/native/pythonWheel.dest/dist/*.whl
LINKML_NATIVE=1 ./mill nativelib.native.pythonWheelTest  # installs it in a throwaway virtualenv
```

Releases ship wheels for Linux, macOS and Windows on x86-64 and ARM64, glibc and musl. The musl
wheels are built natively in an Alpine container — Scala Native needs only clang, so no cross
toolchain is involved.

### The source distribution

There is also a source distribution, for platforms we ship no wheel for. It carries Scala Native IR
and Scala Native's linker instead of a compiled library, and links one during `pip install`, so it
works on anything Scala Native supports. That needs a JVM and clang on the installing machine;
`pip install --only-binary :all: neverblink-linkml` avoids it if you would rather have a wheel.

```shell
LINKML_NATIVE=1 ./mill nativelib.native.sdist      # out/nativelib/native/sdist.dest/*.tar.gz
LINKML_NATIVE=1 ./mill nativelib.native.sdistTest  # installs it in a virtualenv and runs the tests
```

Each release also attaches a prebuilt archive per platform, `linkml-scala-lib-<os>-<arch>`, laid out
as a normal install prefix – `include/`, `lib/` and a pkg-config file – so C, C++ and Rust callers can
use the same library. [`nativelib/smoke.c`](../nativelib/smoke.c) is a worked example in C.
