# URIs and CURIEs

A value of type `uriorcurie` can be written as a full URI, such as `http://www.w3.org/2001/XMLSchema#string`, or as a CURIE, such as `xsd:string`.
A CURIE is a short form: the text before the first colon is a prefix, and the prefix stands for a namespace.
So a CURIE only means something together with the prefixes in force where it is written.

LinkML-Scala follows the approach discussed in [linkml/linkml#3906](https://github.com/linkml/linkml/issues/3906):

- When data is read, expansion is always tried. If it fails, the value is used as a URI.
- Code that works with the data only sees URIs.
- When data is written, each URI is compacted to a CURIE if a known namespace matches it.

## Rules

Expansion:

- The text before the first colon is the prefix. If the prefix is known, it is replaced with its namespace: `xsd:string` becomes `http://www.w3.org/2001/XMLSchema#string`.
- This is also done when the value looks like a URI. With `http: urn:other:` declared as a prefix, `http://example.org/a` becomes `urn:other://example.org/a`. Declaring such a prefix is the author's choice. Without it, `http://example.org/a` is kept as it is written.
- Nothing is guessed. If the prefix is unknown, the value is a URI as it is written, with the prefix as its scheme: without an `xsd` prefix, `xsd:string` is the URI `xsd:string`.
- The same is true in schemas. With no `acme` prefix, `class_uri: acme:Person` is the URI `acme:Person`, and `generate shacl` writes it as `<acme:Person>`, as LinkML-Python does. It is a valid URI, but most likely not what the author meant, so declare every prefix that a schema uses.
- A value without a colon, such as `Person`, is put after the default namespace, if there is one. In a schema, it is the namespace of `default_prefix`, or the schema's `id` if there is no `default_prefix` (see [Default `default_prefix`](implementation_differences.md#default-default_prefix)). So with `id: https://example.org/schema` and no `default_prefix`, `class_uri: Person` is `https://example.org/schema/Person`, and an annotation tag `foo` is `https://example.org/schema/foo`. This keeps generators from writing relative IRIs, such as `<Person>`, that aren't valid in RDF.
- A value without a colon that isn't a valid CURIE, such as `John Doe`, is kept as it is written.

Compaction:

- The longest namespace that the URI starts with is replaced by its prefix.
- The result is only used if it is a valid CURIE with a non-empty local part, and it expands back to the same URI. A local part that starts with `//` is not used either, as `ex://host/a` would look like a URL. Otherwise, the next longest namespace is tried, and the URI is written in full if none fits.
- If two prefixes have the same namespace, the one with the highest precedence (see below) is used, and then the one declared first.

Slots of type `uri` or `curie` are never expanded or compacted, only slots of type `uriorcurie`.

## Schemas

When a schema is loaded, the CURIEs in it are expanded with the prefixes of that schema:

1. The schema's own `prefixes`
2. The `semweb_context` prefixes, if the schema lists `semweb_context` in `default_curi_maps`
3. The prefixes of the LinkML metamodel (see [Emitted prefixes](implementation_differences.md#emitted-prefixes))

An earlier item in this list has precedence over a later one. The prefixes of imported schemas are never used.

A CURIE in an imported schema means what the imported schema's prefixes say, even if the importing schema uses the same prefix for another namespace:

```yaml
# other.yaml
id: https://example.org/other
name: other
prefixes:
  ex: https://other.org/
classes:
  Imported:
    class_uri: ex:Imported # https://other.org/Imported
```

```yaml
# root.yaml
id: https://example.org/root
name: root
prefixes:
  ex: https://root.org/
imports:
  - other
classes:
  Local:
    class_uri: ex:Local # https://root.org/Local
```

`generate linkml` writes one schema with the root's prefixes, so the class URI of `Imported` is written in full, as `https://other.org/Imported`.
In the same way, the `exact_mappings` of `string` from `linkml:types` is written as `http://schema.org/Text` when the root schema doesn't declare `schema`.

A value with an unknown prefix can change its meaning when it is written with other prefixes.
If `other.yaml` above didn't declare `ex`, the class URI of `Imported` would be the URI `ex:Imported`, with `ex` as its scheme.
`generate linkml` writes it as it is, as `ex:Imported`, but the output has the root's `ex` prefix, so reading the output gives `https://root.org/Imported`.
To avoid this, declare every prefix that a schema uses.

To write every URI in full, without compacting it, use the `--full-uris` flag of `generate linkml`, `from owl` and `from ossie`.
In the APIs, it is the `fullUris` option in Scala and JavaScript, and `full_uris` in Python.

Namespaces are used as they are written, as in LinkML-Python. Nothing is added to them, so `GO: http://purl.obolibrary.org/obo/GO_` expands `GO:0008150` to `http://purl.obolibrary.org/obo/GO_0008150`.
A namespace without a separator at the end is a mistake: with `ex: http://example.org`, `ex:Person` is `http://example.orgPerson`.

## Data in Scala

The `LinkmlYamlCodec` of generated Scala classes takes a `PrefixMap` when it decodes and encodes data.
With the default, `PrefixMap.empty`, values are kept as they are written.

A `PrefixMap` has prefixes from two places. The overrides have precedence over the defaults:

1. Overrides, such as the prefixes that a document declares
2. Defaults, for prefixes that are not in the overrides

It can also have a default namespace for values without a colon: `PrefixMap(defaultNamespace = Some("http://example.org/"))` expands `blip` to `http://example.org/blip`.

```scala
import eu.neverblink.linkml.runtime.*
import org.virtuslab.yaml.*

final case class Thing(blep: UriOrCurie) derives LinkmlYamlCodec

val prefixes = PrefixMap(defaults = Seq("ex" -> "http://example.org/"))
val codec = summon[LinkmlYamlCodec[Thing]]
val thing = codec.decode(parseYaml("blep: ex:blip").toOption.get, prefixes = prefixes)
// Thing(Uri("http://example.org/blip"))
codec.encode(thing, prefixes = prefixes).asYaml
// "blep: ex:blip\n"
```

Namespaces in a `PrefixMap` are used as they are written. Nothing is added to them.

### Prefixes declared in the data

The codec doesn't read prefixes that a document declares, such as the `prefixes` of a LinkML schema.
To use them, read them first and pass them as overrides.
`SchemaCodec` does this for schemas.
