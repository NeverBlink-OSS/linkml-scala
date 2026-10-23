# Documentation

The documentation generator turns a LinkML schema into Markdown pages: an index of the schema plus
a page for each class, slot, enum and type. The pages link to each other with relative links, so
they work as they are:

- on GitHub, GitLab and other places that show Markdown files,
- in static site tools like Docusaurus, MkDocs and VitePress (see [Publishing](#publishing)).

## Generating

From the command line, to a folder:

```shell
linkml-scala generate docs --pruning-mode schema --to docs/schema schema.yaml
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

To get one document instead, with the pages as sections that link to each other with anchors, name a
`.md` file in `--to`, or leave `--to` out to write to stdout:

```shell
linkml-scala generate docs --pruning-mode schema --to schema.md schema.yaml
```

Options:

- `--pruning-mode <treeRoot|schema|skip>`: which classes to document. The enums, types and slots
  that these classes use are documented with them.
  - `schema`: the classes defined in the schema, and the classes they use from imported schemas.
    This is usually what you want.
  - `treeRoot`: only the classes that the tree root class reaches.
  - `skip`: everything, including all the types from `linkml:types`. This is the default on the
    command line, as for the other generators.
- `--tree-root <Class>`: with `--pruning-mode treeRoot`, use this class as the tree root.
- `--diagrams=false`: leave out the diagram on each class page.
- `--metadata-language <tag>`: which language to use for titles and descriptions that are given
  in more than one language. Default: `en`.

The generator is also available from JavaScript (`docs()`), Python (`Schema.docs()`) and the C API
(`linkml_docs`), which return the pages as a map from file name to content.

The [playground](https://linkml.neverblink.eu/playground/) has a Documentation target. It shows
the documentation as one page, as GitHub would show it, with the diagrams drawn. The Code tab shows
the Markdown.

## What the pages show

Every page starts with a comment saying that it is generated, then a heading with the element's
name, a deprecation notice if the element is deprecated, and its description. The description is
Markdown, and is copied as it is. In tables, only the first paragraph of a description is shown.

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
- the slots of other classes that have this class as their range,
- a [Mermaid](https://mermaid.js.org/syntax/entityRelationshipDiagram.html) diagram of the class and
  the classes it is linked with. Classes with no links to other classes get no diagram.

A **slot page** has the slot URI, range, cardinality and constraints, and a table of the classes
that declare the slot or change it with `slot_usage`, with the range and cardinality it has in
each.

An **enum page** has a table of the permissible values with their meanings and descriptions, and
the slots that use the enum.

A **type page** has the type URI, its parent type, base, and constraints like `pattern`, and the
slots that use the type.

Cardinality is written as `1` (required), `0..1` (optional), `0..*` or `1..*` (multivalued), or
with the explicit numbers from `exact_cardinality`, `minimum_cardinality` and
`maximum_cardinality`, like `1..3`.

### File names

A page's file name is the element's name, with each character other than letters, digits, `_`, `-`
and `.` replaced by `_`. So the links need no encoding.

When two names would give the same file name, ignoring case (like `Person` and `person`), the
second one gets `_2`. This keeps both pages on macOS and Windows, whose file systems ignore case.
Names that Windows does not allow for files, like `CON`, get a `_` in front.

## Publishing

### GitHub

Commit the folder. GitHub shows the pages, follows the links and draws the diagrams.

### Docusaurus

Docusaurus reads `.md` files as MDX by default, and MDX does not accept HTML comments, a `{`, or a
`<` before a letter in text. Make Docusaurus read `.md` files as plain Markdown, and turn on Mermaid, in
`docusaurus.config.js`:

```js
export default {
  markdown: {
    format: "detect", // .md files are CommonMark, only .mdx files are MDX
    mermaid: true,
  },
  themes: ["@docusaurus/theme-mermaid"],
};
```

The sidebar can be generated from the folders.

### MkDocs

The pages work as they are. For the diagrams with Material for MkDocs, turn on Mermaid in
`mkdocs.yml`:

```yaml
markdown_extensions:
  - pymdownx.superfences:
      custom_fences:
        - name: mermaid
          class: mermaid
          format: !!python/name:pymdownx.superfences.fence_code_format
```

### VitePress

The pages work as they are, but VitePress reads `{{ ... }}` in text as Vue code, so check the
descriptions for it. For the diagrams, add a Mermaid plugin such as `vitepress-plugin-mermaid`.
