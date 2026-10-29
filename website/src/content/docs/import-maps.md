---
title: Import maps
description: Load imported schemas from somewhere else, without changing the schema.
---

An import map tells LinkML-Scala where to load an imported schema from, without changing the schema.
Use one when a schema imports another one by URI or CURIE, and you have that schema somewhere else: a local copy, a cache of downloaded dependencies, or a newer version.

The map is a JSON object, in the same format as the `--importmap` file of LinkML-Python. Each key is an import, and each value is the location to load it from:

```json
{
  "https://example.org/units/core": "deps/units.yaml"
}
```

```yaml
# model.yaml
id: https://example.org/model
name: model
prefixes:
  units: https://example.org/units/
imports:
  - linkml:types
  - units:core
```

```shell
ohi linkml validate --import-map imports.json model.yaml
```

`units:core` is loaded from `deps/units.yaml`. The schema keeps `units:core` in its `imports`.

## How imports are matched

Each import is first turned into a lookup key:

1. A full URI, such as `https://example.org/units/core` or `urn:example:core`, is used as it is.
2. A CURIE, such as `units:core`, is expanded with the prefixes of the schema that contains the import. These are the prefixes of the LinkML metamodel (`linkml`, `xsd`, `rdf`, `skos`, ...), the `semweb_context` ones if the schema lists it in `default_curi_maps`, and the schema's own `prefixes`. The prefixes of other schemas are never used. A CURIE with an unknown prefix is reported as an import error.
3. A relative path, such as `./core` or `core`, is joined to the directory of the importing schema. An absolute path, such as `/schemas/core` or `C:\schemas\core`, is used as it is.
4. The `.yaml` or `.yml` extension is ignored.

So `units:core`, `https://example.org/units/core` and `https://example.org/units/core.yaml` all find the same entry, and the schema is loaded once.

Two schemas can use the same CURIE for different schemas. If `a.yaml` declares `base: https://a.org/` and `b.yaml` declares `base: https://b.org/`, then `base:core` in `a.yaml` is `https://a.org/core`, and in `b.yaml` it is `https://b.org/core`. Each can have its own location.

## Entries

Each entry maps an import to a location.

- **Exact entries.** `"https://example.org/units/core": "deps/units.yaml"` matches only that import.
- **Prefix entries.** A key that ends with `/` or `#` matches every import that starts with it, and that start is replaced. `"https://example.org/units/": "deps/units/"` loads `https://example.org/units/core` from `deps/units/core.yaml`.

An exact entry has precedence over a prefix entry. When several prefix entries match, the longest one is used.

Keys and locations are full URIs or paths, not CURIEs. A key such as `linkml:types` or `units:core` is rejected: a key does not belong to any schema, so it is not known which URI its prefix stands for. Write the full URI instead, such as `https://w3id.org/linkml/types`. CURIE imports in schemas are still matched, as their lookup key is a full URI.

Two entries that are the same import, such as `https://w3id.org/linkml/types` and `https://w3id.org/linkml/types.yaml`, must have the same location.

A location can be matched again. With `"a": "b"` and `"b": "c"`, `a` is loaded from `c`. A chain that leads back to an import already seen is reported as an error.

## Locations

A location is used as it is. A relative location is not joined to the directory of the importing schema, or to the directory of the JSON file:

- With the CLI, `load_file()` in Python, and `linkml_load_file()` in C, it is relative to the current directory.
- With the in-memory loaders (`load_string()` and `load_path()` in Python, `loadFromString()` and `loadFromPath()` in JavaScript), it is a key of the map of schema texts.

The relative imports of a schema loaded from a location are joined to that location. If `https://example.org/units/core` is loaded from `deps/units/core.yaml`, and that schema imports `./quantities`, it is loaded from `deps/units/quantities.yaml`.

An import map also applies to:

- The built-in schemas. `"https://w3id.org/linkml/types": "local/types.yaml"` loads the metamodel types from a local copy.
- The schema you load. With `"https://example.org/model": "local/model.yaml"` in `imports.json`, `ohi linkml validate --import-map imports.json https://example.org/model` loads `local/model.yaml`.

## Two files with the same schema id

If two files with the same `id` and different content are loaded, loading stops with an error that names both files.
This can happen when an import misses its entry and loads a stale copy of a schema that is also loaded from its location.
Two files with the same content are loaded without an error.

## Usage

### CLI

`generate` and `validate` take `--import-map FILE`, the path of the JSON file:

```json
{
  "https://example.org/units/": "deps/units/",
  "https://w3id.org/linkml/types": "local/types.yaml"
}
```

```shell
ohi linkml generate json-schema --import-map imports.json model.yaml
```

### Python

`import_map=` takes a mapping, or the path of a JSON file:

```python
schema = linkml_scala.load_file(
    "model.yaml",
    import_map={"https://example.org/units/": "deps/units/"},
)
schema = linkml_scala.load_file("model.yaml", import_map="imports.json")
```

`load_string()` and `load_path()` take `import_map=` too. Their locations are keys of the `imports` map:

```python
schema = linkml_scala.load_path(
    "model.yaml",
    {"model.yaml": model, "deps/units.yaml": units},
    import_map={"https://example.org/units/core": "deps/units.yaml"},
)
```

### JavaScript / TypeScript

`loadFromString()` and `loadFromPath()` take the import map as their fourth argument. Locations are keys of `imports`, the second argument:

```js
const { view } = LinkML.loadFromPath(
  "model.yaml",
  { "model.yaml": model, "deps/units.yaml": units },
  true,
  { "https://example.org/units/core": "deps/units.yaml" },
);
```

To use a JSON file, read it and pass `JSON.parse` of its text.

### C

The options of `linkml_load_file()` and `linkml_load_string()` take an `importMap` object:

```json
{"importMap": {"https://example.org/units/": "deps/units/"}}
```

### Scala

Build an `ImportMap`, from entries with `ImportMap(...)` or from JSON text with `ImportMap.fromJson`, and wrap the importer you use with `ImportMapImporter`:

```scala
val view: Either[Seq[SchemaFatal], SchemaView] =
  ImportMap.fromJson(java.nio.file.Files.readString(java.nio.file.Path.of("imports.json")), "imports.json")
    .left.map(Seq(_))
    .flatMap(map => SchemaView.loadSchemaViewFromUri("model.yaml", ImportMapImporter(map, FileSystemImporter)))
```

`ImportMapImporter` passes all other calls to the importer it wraps, so it works with any importer.
Wrapped importers can be stacked; the outer map has precedence.

To find the same files as the loader, for example to track dependencies, use `ImportMap.lookupKey` for each import and `ImportMap.resolve` for its lookup key.
