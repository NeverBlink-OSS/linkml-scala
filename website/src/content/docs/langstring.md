---
title: Language strings in generators
description: How each generator writes a value of the `langstring` type.
---

A value of the `langstring` type, imported from `linkml:types`, is a plain string or a non-empty mapping from language tag to text:

```yaml
name: Book
```

```yaml
name:
  en: Book
  pl: Książka
```

Generators accept both forms:

- JSON Schema: `anyOf` a string and a non-empty object with string values.
- TypeScript: `string | Record<string, string>`.
- Pydantic: `str | Annotated[dict[str, str], Field(min_length=1)]`.
- SHACL: `sh:or ( [ sh:datatype xsd:string ] [ sh:datatype rdf:langString ] )`. A single-valued slot holds one plain string, or texts in different languages (`sh:uniqueLang true`).
- OWL: `rdf:PlainLiteral`. A single-valued slot is not functional, as a text in several languages is one literal per language.
