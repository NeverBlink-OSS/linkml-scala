# Python bindings (experimental)

LinkML-Scala compiled to a native shared library, called from Python through `ctypes`. No JVM, no
subprocess per schema, and no dependency on the `linkml` Python package.

> [!WARNING]
> This is an experiment. Nothing here is published to PyPI yet and the API is not stable. 
> Feedback is welcome in [Discussions](https://github.com/NeverBlink-OSS/linkml-scala/discussions).

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

## Building it

You need JDK 17+ (Mill downloads GraalVM itself) and Python 3.10 or newer, tested on 3.13. Then:

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

## How it works

The library exports two C functions, and everything goes through them as JSON:

```c
char* linkml_call(graal_isolatethread_t*, char*);   // JSON request in, JSON response out
void  linkml_free(graal_isolatethread_t*, char*);   // release a response
```

```json
{"op": "generate", "handle": 7, "generator": "shacl", "options": {"open": true}}
{"ok": true, "output": "<https://example.org/Person> <...#type> <...#NodeShape> .\n..."}
```

The ops are `version`, `load`, `generate`, `lint` and `close`. Keeping it to one function means the C
ABI does not change when a generator gains an option – only the JSON inside the call does. A loaded
schema is an integer handle into a table on the Scala side.

Python creates one GraalVM isolate per process and attaches each calling thread to it on first use.

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
schema.json_schema(open=False, tree_root=None, tree_root_inline_type=None)
schema.shacl(open=False, only_classes_from_root_schema=False)
schema.rdfs(only_classes_from_root_schema=False)
schema.linkml(skip_derivation=False, pruning_mode="skip", tree_root=None, format="yaml")
schema.table_schema(tree_root=None)
schema.graphql(pruning_mode="skip", tree_root=None)
schema.er_diagram(pruning_mode="skip", tree_root=None, optional_marker=True)
schema.scala(package, generate_emit_prefixes=True)  # -> {filename: source}
```

Every one of these returns a string, except `scala()`, which returns a filename-to-source dict. The
options match the CLI's flags, including the defaults – note that `pruning_mode` defaults to `"skip"`
here and in the CLI, but to `"treeRoot"` in the JavaScript bindings.

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

Rough estimates, based on a few local runs.

**Binding overhead.** On `tests/resources/models/inheritance/model.yaml`, against shelling out to the
native CLI binary:

| | Time |
|---|---|
| CLI subprocess, JSON Schema | 4.2 ms |
| Bindings, JSON Schema (schema already loaded) | <0.05 ms |
| Bindings, load + JSON Schema | 0.5 ms |
| CLI subprocess, 6 generators (reparses each time) | 24.8 ms |
| Bindings, 6 generators from one load | 0.9 ms |

The gap is mostly process startup and reparsing the schema per invocation, both of which the bindings
avoid. The JSON round trip does not show up at this scale.

**Against LinkML (Python).** A synthetic 300-class schema, generated from scratch each time:

| | LinkML (Python) | Bindings | |
|---|---|---|---|
| JSON Schema | 204 ms | 11 ms | 18× |
| SHACL | 363 ms | 13 ms | 28× |

In line with [the existing benchmarks](benchmarks.md), which is what you would hope for: it is the
same generator code.

**Startup.** `import linkml_scala` takes 10 ms, and the first load – which also creates the isolate –
another 13 ms for the LinkML metamodel. For comparison, importing two generators from the `linkml`
Python package takes 450 ms.

## TODO

1. `pyproject.toml` and a wheel per platform, with the library bundled as package data, built in the
   existing release workflow next to the native CLI binaries.
2. Type stubs, or type hints good enough to not need them. The reports are plain dicts today; a
   `TypedDict` generated from `validation-report.yaml` would be better, and would help the
   TypeScript bindings too (see [#127](https://github.com/NeverBlink-OSS/linkml-scala/issues/127)).
3. A raw path for large outputs that skips JSON string escaping, if it ever shows up in a profile. Or a custom simple serialization format with prepended length (pseudo-Protobuf).
