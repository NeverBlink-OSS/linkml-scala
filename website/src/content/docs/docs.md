---
title: Documentation
description: The documentation generator, which writes Markdown pages for a LinkML schema.
---

The documentation generator turns a LinkML schema into Markdown pages: an index of the schema plus
a page for each class, slot, enum and type. The pages link to each other with relative links, so
they work as they are:

- on GitHub, GitLab and other places that show Markdown files,
- in static site tools like Docusaurus, MkDocs and VitePress (see [Publishing](#publishing)).

## Generating

From the command line, to a folder:

```shell
linkml-scala generate docs --to docs/schema schema.yaml
```

This writes:

```
docs/schema/
  index.md          the schema, and a table of its classes, slots, enums and types
  classes/<name>.md
  slots/<name>.md
  enums/<name>.md
  types/<name>.md
```

Before writing, it deletes the pages that an earlier run wrote in these four folders, so that the
pages of removed elements don't linger. It knows them by the comment they start with. Other files
are kept.

To get one document instead, with the pages as sections that link to each other with anchors, name a
`.md` file in `--to`, or leave `--to` out to write to stdout:

```shell
linkml-scala generate docs --to schema.md schema.yaml
```

Options:

- `--pruning-mode <treeRoot|schema|skip>`: which classes to document. The enums, types and slots
  that these classes use are documented with them.
  - `schema`: the classes defined in the schema, and the classes they use from imported schemas.
    This is the default, unlike for the other generators.
  - `treeRoot`: only the classes that the tree root class reaches.
  - `skip`: everything, including all the types from `linkml:types`, whether the schema uses them
    or not.
- `--tree-root <Class>`: with `--pruning-mode treeRoot`, use this class as the tree root.
- `--metadata-language <tag>`: which language to use for titles and descriptions that are given
  in more than one language. Default: `en`.

The generator is also available from JavaScript (`docs()`), Python (`Schema.docs()`) and the C API
(`linkml_docs`). They return the pages as a map from file name to content. With the `singleFile`
option (`single_file` in Python), the map has only `docs.md`, with all pages as one document.

The [playground](https://linkml.ohi.dev/playground/) has a Documentation target. It shows
the documentation as one page, as GitHub would show it. The Code tab shows the Markdown.

## What the pages show

Every page starts with a comment saying that it is generated, then a heading with the element's
name, a deprecation notice if the element is deprecated, and its description. The description is
Markdown, and is copied as it is, so a mistake in it, such as an unclosed `<pre>`, can hide the
rest of the page. Only a code block that the description opens with a fence (```` ``` ```` or
`~~~`) but doesn't close is closed after it. In a table that links to an element's page, only the first
paragraph of its description is shown. The descriptions of permissible values and examples, which
have no page of their own, are shown in full, with `<br><br>` between paragraphs.

Headings, links and tables use the element's name as it is written in the schema. Under the
description, a page also lists the element's other names, each only when the element has it or
when it is different from that name:

- **Title:** the `title`, a name for people to read.
- **Canonical name:** the name re-cased as is usual for the kind of element, like `PascalCase`
  for classes and enums and `snake_case` for slots. It is the end of the element's URI when there
  is no `class_uri`, `slot_uri` and so on.
- **Name in data:** for a slot, its `alias`, which is its name in JSON, YAML and other data that
  is not RDF. In the slot table of a class, a slot with an `alias` has it after its name, like
  `birth date (birthDate in data)`.
- **Other names:** the `aliases`, which are only for people and searches, and are not used in
  data.
- **Names in code:** for classes, enums and slots, the names that the Scala, TypeScript, Python
  and GraphQL generators give the element. Languages that give the same name are listed together.

In the table of permissible values, a value with an `alias` has it after the value, and a value
with a `title` has it before its description.

The **index page** has the schema's title, description, ID, version, license and tree root, then a
table each of classes, slots, enums and types.

A **class page** has:

- the class URI, its parent (`is_a`), mixins, and the classes that extend it,
- an inheritance tree, from the topmost ancestor down to the direct children,
- a table of all slots of the class, its own and inherited ones, with their range, cardinality,
  description, and the class they are inherited from. Ranges with `slot_usage` applied are
  shown as they are in this class. A class range that is a reference rather than inlined is
  marked `(reference)`.
- its unique keys, with their slots, and how many `rules` it has, which the page doesn't show,
- the slots of other classes that have this class as their range.

A **slot page** has the slot URI, range, cardinality and constraints, and a table of the classes
that declare the slot or change it with `slot_usage`, with the range and cardinality it has in
each.

An **enum page** has a table of the permissible values with their meanings and descriptions, and
the slots that use the enum. If the values also come from elsewhere, a "Values from" section says
where: the enums it `inherits`, the value sets it includes or excludes (`include`, `minus`), its
`concepts`, its `code_set`, or the ontology query in `reachable_from`.

A **type page** has the type URI, its parent type, base, and constraints like `pattern`, and the
slots that use the type.

Cardinality is written as `1` (required), `0..1` (optional), `0..*` or `1..*` (multivalued), or
with the explicit numbers from `exact_cardinality`, `minimum_cardinality` and
`maximum_cardinality`, like `1..3`.

### File names

A page's file name is the element's base name, as `generate translation --target docs` gives
it: lower case ASCII letters and digits, with words separated by `_`. So `PersonName` is in
`classes/person_name.md`. The links need no encoding, and the names work on any file system.

When two names give the same base name (like `Person` and `person`), the second one gets `_2`.
Which one is second depends on their order in the schema, so adding or moving such an element can
change the file names of the others, and links to them from outside the documentation.

## Adding sections

From Scala, extend `DocsGenerator` and override `indexSections` or `elementSections` to add
sections at the end of the index page or of each element's page. Write them with the `Markdown`
writer they get:

- Headings get the right level, both on a page of its own and in one document. `heading` writes
  a fixed title. For a title from the schema, use `startHeading`, then `text`, then `endBlock`.
- `text` and `code` escape text from the schema, so that it shows as written.
- `codeBlock` writes a fenced code block whose fence cannot be ended by the text in it.

For example, a Mermaid diagram of the whole schema on the index page, which GitHub draws:

```scala
import eu.neverblink.linkml.generator.docs.{DocsGenerator, DocsSite, Markdown}
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.schemaview.SchemaView

class DocsWithDiagram(using SchemaView) extends DocsGenerator {
  override protected def indexSections(md: Markdown, site: DocsSite): Unit = {
    md.heading(2, "Diagram")
    md.codeBlock("mermaid", ErDiagramGenerator().serialize(ErDiagramGenerator.Options()))
  }
}
```

`DocsSite` tells which classes, slots, enums and types are documented, and how they are used.

## Publishing

### GitHub

Commit the folder. GitHub shows the pages and follows the links.

### Docusaurus

Docusaurus reads `.md` files as MDX by default, and MDX does not accept HTML comments, a `{`, or a
`<` before a letter in text. Make Docusaurus read `.md` files as plain Markdown in
`docusaurus.config.js`:

```js
export default {
  markdown: {
    format: "detect", // .md files are CommonMark, only .mdx files are MDX
  },
};
```

The sidebar can be generated from the folders.

### MkDocs

The pages work as they are.

### VitePress

The pages work as they are, but VitePress reads `{{ ... }}` in text as Vue code, so check the
descriptions for it.
