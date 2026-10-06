# LinkML <-> OWL

The `owl` generator writes an [OWL 2](https://www.w3.org/TR/owl2-overview/) ontology for a LinkML schema, and the `owl` importer reads an ontology into a LinkML schema. The two are each other's reverse and should preserve most axioms of an OWL ontology.

OWL can express things LinkML cannot (property chains, defined classes, individuals), and LinkML can express things OWL cannot (`tree_root`, `inlined`, identifiers). Those are lost on the way. The importer lists what it left out.

The generator aims for OWL 2 DL. SAREF, SOSA, gist, PROV-O and D3FEND, after a trip through LinkML, pass ROBOT's DL profile check (`robot validate-profile --profile DL`, run by hand).

## Generating OWL

```shell
linkml-scala generate owl schema.yaml --to schema.owl.ttl
```

| Option                 | Meaning                                                                                                                 |
|------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `--format`             | `ttl` (Turtle, the default) or `nt` (N-Triples).                                                                        |
| `--only-root-schema`   | Describe only the root schema, with an `owl:imports` for each schema it imports. By default the imports are merged in.  |
| `--metadata-profile`   | `rdfs` writes descriptions as `rdfs:comment`, which most ontologies use and OWL tools show (the default). `linkml` uses `skos:definition`, as the LinkML metamodel says and Python's `gen-owl` does. |
| `--permissible-values` | `individual` makes permissible values named individuals (the default). `class` makes them subclasses of the enum.        |

From JavaScript, `LinkML.owl(view, onlyRootSchema, metadataProfile, permissibleValues, format)`. From Python, `schema.owl(format="nt")`.

### Mapping LinkML to OWL

| LinkML                                                     | OWL                                                                                                    |
|------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| schema                                                     | `owl:Ontology` with the schema `id` as its IRI                                                         |
| `prefixes`, `default_prefix`                               | `sh:declare` on the ontology, and `vann:preferredNamespacePrefix` / `vann:preferredNamespaceUri`       |
| class                                                      | `owl:Class`                                                                                            |
| `is_a`, `mixins`                                           | `rdfs:subClassOf` the parents                                                                          |
| a slot of the class (in `slots`, `attributes`, `slot_usage`) | restrictions on the class: `owl:allValuesFrom` the range, cardinalities, `owl:hasValue`, `owl:someValuesFrom` |
| `required`, `minimum_cardinality`, `maximum_cardinality`   | `owl:minCardinality`, `owl:maxCardinality`, or `owl:cardinality` when the two are equal. On a transitive slot, its parents and inverses, which OWL 2 DL allows no counts on, `required` is `owl:someValuesFrom owl:Thing` and the counts are skipped |
| a single-valued slot                                       | `owl:maxCardinality 1`, and `owl:FunctionalProperty` when it is single-valued everywhere it is used    |
| `equals_string`, `equals_number`                           | `owl:hasValue`                                                                                         |
| `has_member`, and more `has_member` under `all_of`         | `owl:someValuesFrom`, one each                                                                         |
| `abstract` with two or more `is_a` children                | `rdfs:subClassOf` the union of the children                                                            |
| `disjoint_with`, `children_are_mutually_disjoint`          | `owl:disjointWith`, `owl:AllDisjointClasses`                                                           |
| `unique_keys`                                              | `owl:hasKey`                                                                                           |
| `exact_mappings` to a class or slot of the same schema     | `owl:equivalentClass`, `owl:equivalentProperty`                                                        |
| slot                                                       | `owl:ObjectProperty`, or `owl:DatatypeProperty` when every use of it has a type as its range           |
| `implements: [owl:DatatypeProperty]` and friends           | the property kind asked for, as in `gen-owl`                                                           |
| `range`, `any_of`                                          | `rdfs:range`, a union for `any_of`                                                                     |
| `domain`, `domain_of`                                      | `rdfs:domain`, a union for `domain_of`                                                                 |
| `is_a`, `mixins` on a slot                                 | `rdfs:subPropertyOf`                                                                                   |
| `inverse`                                                  | `owl:inverseOf`                                                                                        |
| `transitive`, `symmetric` and the other characteristics    | `owl:TransitiveProperty` and so on                                                                     |
| enum                                                       | `owl:Class` equivalent to `owl:oneOf` its permissible values                                           |
| permissible value                                          | `owl:NamedIndividual` with the `meaning` as its IRI, and `skos:notation` for its text when that is not the IRI's local name |
| type                                                       | its `uri`; with constraints, a datatype restriction on it (`xsd:integer[>= 0]`)                        |
| `pattern`, `minimum_value`, `maximum_value`                | `xsd:pattern`, `xsd:minInclusive`, `xsd:maxInclusive`. An XSD pattern matches the whole value, so `^abc$` is written `abc`, and `abc` is written `.*abc.*` |
| `title`, or the name when there is no title                | `rdfs:label`, tagged with `in_language` when the schema has one. A term outside the schemas' own namespaces gets no label made up from its name |
| `description`                                              | `rdfs:comment` (or `skos:definition` with `--metadata-profile linkml`)                                  |
| other documentation (`aliases`, `comments`, `see_also`, mappings, …) | the property the LinkML metamodel gives it, such as `skos:altLabel`, `skos:note`, `rdfs:seeAlso` |
| `annotations`                                              | annotations with the tag as the property                                                               |
| `deprecated`                                               | `owl:deprecated true`                                                                                  |

The LinkML-to-OWL generator declares every annotation property that is used, which OWL 2 DL requires. The same goes for every datatype that is not in OWL 2's datatype map, such as `xsd:date`. RDF's, RDFS's, OWL's and XSD's own terms are never declared.

### Differences from Python's `gen-owl`

These differences are in place mostly to make the OWL describe the same RDF data as the RDFS and SHACL generators, which we think is a more consistent approach.

- **IRIs are the ones the data uses**: `class_uri`, `slot_uri`, `enum_uri` and the permissible value `meaning`, as in this project's RDF, RDFS and SHACL. `gen-owl` uses the schema's own namespace by default (`--use-native-uris`).
- **Identifier slots are not properties.** In RDF, an identifier is the IRI of the node.
- **Slots with a `uri` or `uriorcurie` range, or with an `implicit_prefix`, are object properties without a range**, since their values are IRIs in RDF, as in the SHACL generator.
- **Permissible values are individuals** of a class defined by `owl:oneOf`. `gen-owl` makes them classes by default, which is still possible in LinkML-Scala with the `--permissible-values class` option.
- **Descriptions are `rdfs:comment`** by default, rather than the metamodel's `skos:definition`. If you select the `--metadata-profile linkml` option, descriptions will be written as `skos:definition`, as `gen-owl` does by default.
- **`owl:allValuesFrom` restrictions that repeat a property's range are kept** for the slots a class lists itself. They record which classes use which slots, which the importer needs. A class that only refines an inherited slot in `slot_usage` gets one only when it narrows the range.
- `type_designator` rules, `rules` and `classification_rules` are not written.

**Improvements over Python `gen-owl`:**

- **`pattern` is translated** to XSD's form, which matches the whole value: `gen-owl` writes it as it is, so `^…$` patterns match nothing.
- **An abstract class with only one subclass gets no covering axiom.** Consider class B, which is a subclass of an abstract class A. `gen-owl` in this case writes "every A is a B", and since every B is already an A, a reasoner treats the two classes as the same.
- **`rdfs:label` is the title**, with the name when there is no title, rather than always the name.
- **A single-valued slot is also marked as an `owl:FunctionalProperty`** when all classes that use this property treat it as single-valued.
- **An attribute's property gets an `rdfs:range`** when every use of it has the same range.
- **The announced new defaults are used**: no `owl:minCardinality 0`, which says nothing, and one `owl:cardinality` where the lower and upper bounds are equal.
- **A `license` that is a link is written as an IRI**.

## Importing OWL

The importer reads Turtle and N-Triples:

```shell
linkml-scala from owl ontology.ttl --to schema.yaml
```

Add the `--list-not-imported` option to list what could not be imported is listed on stderr:

```
Not imported: Property chain, which LinkML cannot express (29): has_feature_kind: saref:hasFeatureKind o skos:broader; …
```

Prefix names come from `sh:declare` (which the generator writes), the document's `@prefix` lines, the config, a list of well-known ones (`rdfs`, `skos`, `dcterms`, `prov`, `sosa`, …), and the last path segment of other namespaces.

### Mapping OWL to LinkML

| OWL                                                         | LinkML                                                                                                              |
|-------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `owl:Ontology`                                              | the schema, with the ontology IRI as `id`                                                                           |
| class, or a class used without a declaration                | class, named after the local part of its IRI, with `class_uri` when that differs from what LinkML would make. A class from another ontology becomes a class that stands for it, unless an imported schema has it (see below) |
| named superclasses                                          | `is_a` for the first superclass from the same namespace as the subclass. `mixin` for other superclasses. |
| a superclass cycle                                          | broken, since LinkML has none: a parent is left out and reported                                                   |
| `owl:allValuesFrom` on a class                              | the slot in the class's `slots`, with `slot_usage` `range` when it narrows the property's range                   |
| `owl:someValuesFrom`                                        | `required: true`; when it narrows the range, also `has_member: {range: …}` (another one goes under `all_of`)      |
| cardinalities                                               | `required`, `minimum_cardinality`, `maximum_cardinality`, `exact_cardinality`                                      |
| `owl:hasValue`                                              | `equals_string`                                                                                                     |
| a union of a class's children as its superclass             | `abstract: true`                                                                                                    |
| `owl:equivalentClass` to a named class                      | `exact_mappings`                                                                                                    |
| `owl:equivalentClass` to an expression                      | the expression read as superclasses (the "only if" half), and reported                                             |
| `owl:disjointWith`, `owl:AllDisjointClasses`, `owl:disjointUnionOf` | `disjoint_with`, `children_are_mutually_disjoint`                                                         |
| `owl:hasKey`                                                | `unique_keys`                                                                                                       |
| object or data property                                     | slot, snake_cased, with `slot_uri`                                                                                  |
| annotation property of the ontology's own that it describes | slot with `implements: [owl:AnnotationProperty]`                                                                    |
| `rdfs:range`                                                | `range`, `any_of` for a union, `Any` (from `linkml:extended_types`) when there is none or when it mixes classes and datatypes |
| `rdfs:domain`                                               | `domain` and the class's `slots`; for a union, `domain_of` and each class's `slots`                                 |
| `owl:FunctionalProperty`                                    | single-valued. Other slots are multivalued, as OWL allows any number of values                                      |
| `rdfs:subPropertyOf`                                        | `is_a` and `mixins`. A parent from another ontology becomes a slot that stands for it, of the same kind as the child |
| `owl:inverseOf`, characteristics, `owl:equivalentProperty`  | `inverse`, `transitive` and friends, `exact_mappings`                                                               |
| class defined by `owl:oneOf`                                | enum. The text of a value is its `skos:notation`, else its local name                                               |
| class with only individuals, used as a range                | enum (turn off with `enums_from_individuals: false`). The class, or one of its parents, can be a range through `schema:rangeIncludes` too; its named equivalents become `exact_mappings`; and its parents become `is_a` and `mixins`, as for classes |
| XSD and RDF datatypes                                       | LinkML's types, plus a type for each other one that is declared or used (`int`, `gYear`, `anyURI`, …)                |
| datatype restriction                                        | `minimum_value`, `maximum_value`, `pattern` on the slot or the type (`xsd:pattern` `abc` becomes `^abc$`)           |
| annotations                                                 | the metaslots in the config's `metadata` table, then LinkML `annotations` for the rest. The default table means the usual vocabularies, whatever prefixes the ontology declares |
| language tags                                               | the schema's `in_language` when nearly all text written for people (titles, descriptions, comments and so on, not code examples or identifiers) is in one language. Text in another language, or in any language when the schema has none, keeps it, such as `title: {en: Minute}`. This holds for titles, descriptions, aliases, comments, notes and keywords. In a list (`comments` and so on), texts with a different language each are one entry; otherwise each text is an entry of its own. A label that repeats the name counts as one of the languages when there are labels in others, or when it is the only title. Annotations lose their tags |

**Not imported:** individuals that are not part of an enum, property values of individuals, property chains, restrictions on inverse properties, general class axioms, inverse functional properties, disjoint properties, `owl:imports` that the config does not map, what the ontology says about terms of an imported schema, and subproperties of annotation properties and of RDF, RDFS and OWL's own. Restrictions that do not fit `slot_usage` are kept in the class's `notes`, like `OWL: inverse ssn:hasInput min 1`.

### Imports

LinkML refers to classes and slots by name, so a term from another ontology needs an element in the schema. By default, the importer makes stubs for referenced-but-not-defined classes, such as `Animal` with `class_uri: other:Animal`.

When the imported ontology has a LinkML schema (e.g., it was converted to LinkML earlier), map the import to it in the config's `imports`:

```yaml
imports:
  http://www.w3.org/ns/sosa/: sosa   # the ontology IRI -> the LinkML import
```

The schema then imports `sosa`, and the importer reads that schema to use its classes, slots, enums and types by name, matching them by IRI. A relative import is resolved against where the schema is written (the directory of `--to`, or the current one).

In the other direction, `--only-root-schema` writes an `owl:imports` for each imported schema, with the schema's `id` as the ontology IRI. A schema imported from OWL has the ontology IRI as its `id` (or, for a vocabulary without an `owl:Ontology` such as DC terms, its namespace), so the two line up.

### Config

`--config` takes the path to a YAML file. The JS and Python functions take the text of the config itself instead. The config's format is defined as a LinkML schema in [`model/owl-import-config.yaml`](../model/owl-import-config.yaml). Properties and classes can be written as CURIEs over the ontology's prefixes, the config's `prefixes`, or the well-known ones.

```yaml
schema_id: https://example.org/my-schema   # default: the ontology IRI
name: my_schema                            # default: the ontology's rdfs:label if it is a usable name, else the default prefix
default_prefix: d3f                        # default: the namespace most terms are in
prefixes:
  d3f: http://d3fend.mitre.org/ontologies/d3fend.owl#
naming:
  classes: keep             # keep | snake | pascal | upper_snake
  slots: snake              # default snake: hasValue becomes has_value
  permissible_values: keep
renames:                    # names for particular terms, by IRI or CURIE
  d3f:date: date_value
metadata:                   # where each metaslot is read from, in order; replaces the default list for that metaslot
  description: [d3f:definition, rdfs:comment]
annotations: true           # keep other annotations as LinkML annotations
multivalued: true           # slots are multivalued unless functional
some_values_from: required  # required | range (also narrows the range, as SAREF-LinkML does)
enums_from_individuals: true
datatypes:                  # LinkML types for datatype IRIs
  xsd:gYear: string
imports:                    # LinkML schemas to import in place of owl:imports (see Imports); others are reported
  http://www.w3.org/ns/sosa/: ./sosa
```

The default `metadata` table reads each metaslot from the property the LinkML metamodel gives it, then from the common alternatives:

| Metaslot          | Read from, in order                                                       |
|-------------------|---------------------------------------------------------------------------|
| `title`           | `rdfs:label`, `dcterms:title`, `dc:title`, `skos:prefLabel`                 |
| `description`     | `skos:definition`, `dcterms:description`, `dc:description`, `obo:IAO_0000115`, `rdfs:comment` |
| `aliases`         | `skos:altLabel`, `oboInOwl:hasExactSynonym`, `rdfs:label`, `skos:prefLabel`  |
| `comments`        | `skos:note`, `rdfs:comment`, `skos:scopeNote`                               |
| `notes`           | `skos:editorialNote`, `skos:historyNote`, `skos:changeNote`                 |
| `examples`        | `skos:example`, `obo:IAO_0000112`                                           |
| `see_also`        | `rdfs:seeAlso`                                                             |
| `contributors`    | `dcterms:contributor`, `dcterms:creator`, `dc:creator`, `dc:contributor`    |
| `created_on`      | `pav:createdOn`, `dcterms:created`, `dcterms:issued`                        |
| `last_updated_on` | `pav:lastUpdatedOn`, `dcterms:modified`                                     |
| `license`         | `dcterms:license`, `dc:rights`, `cc:license`                                |
| `version`         | `pav:version`, `owl:versionInfo` (and `owl:versionIRI` on the ontology)     |
| others            | the property from the metamodel, such as `skos:exactMatch` for `exact_mappings` |

A value goes to the first metaslot that lists its property and still has room. So a class with two `rdfs:comment`s gets the first as its `description` and the second in `comments`. A label that is just the element's name is dropped, and so is a comment that repeats the description.
