// Starting points for the Load example button - one per input format. The Ossie one is what the
// Ossie generator makes of the LinkML one, so the two examples round-trip into each other.

export const EXAMPLE_SCHEMA = `id: https://example.org/library
name: library
description: A tiny example schema, showing classes, slots, enums and a tree root.
prefixes:
  linkml: https://w3id.org/linkml/
  library: https://example.org/library/
emit_prefixes:
  - library
default_range: string
imports:
  - linkml:types

enums:
  LoanStatus:
    permissible_values:
      AVAILABLE:
      ON_LOAN:
      LOST:

classes:
  Book:
    description: A book that can be borrowed from the library.
    attributes:
      title:
        required: true
      isbn:
        description: International Standard Book Number
      published_year:
        range: integer
      status:
        range: LoanStatus
      author:
        range: Person
        inlined: true

  Person:
    description: An author or a library member.
    attributes:
      name:
        required: true
      email:

  Library:
    tree_root: true
    attributes:
      name:
        required: true
      books:
        range: Book
        multivalued: true
        inlined_as_list: true
`;

export const EXAMPLE_OSSIE = `version: 0.2.0.dev0
name: library
description: A tiny example schema, showing classes, slots, enums and a tree root.
ontology:
  - concept: Book
    type: EntityType
    description: A book that can be borrowed from the library.
    requires:
      - Book.title
    relationships:
      - name: author
        roles:
          - concept: Person
        multiplicity: ManyToOne
        verbalizes:
          - "{Book} author {Person}"
      - name: isbn
        description: International Standard Book Number
        roles:
          - concept: String
        multiplicity: ManyToOne
        verbalizes:
          - "{Book} isbn {String}"
      - name: published_year
        roles:
          - concept: Integer
        multiplicity: ManyToOne
        verbalizes:
          - "{Book} published year {Integer}"
      - name: status
        roles:
          - concept: LoanStatus
        multiplicity: ManyToOne
        verbalizes:
          - "{Book} status {LoanStatus}"
      - name: title
        roles:
          - concept: String
        multiplicity: ManyToOne
        verbalizes:
          - "{Book} title {String}"
  - concept: Library
    type: EntityType
    requires:
      - Library.name
    relationships:
      - name: books
        roles:
          - concept: Book
        verbalizes:
          - "{Library} books {Book}"
      - name: name
        roles:
          - concept: String
        multiplicity: ManyToOne
        verbalizes:
          - "{Library} name {String}"
  - concept: Person
    type: EntityType
    description: An author or a library member.
    requires:
      - Person.name
    relationships:
      - name: email
        roles:
          - concept: String
        multiplicity: ManyToOne
        verbalizes:
          - "{Person} email {String}"
      - name: name
        roles:
          - concept: String
        multiplicity: ManyToOne
        verbalizes:
          - "{Person} name {String}"
  - concept: LoanStatus
    type: ValueType
    extends:
      - String
    requires:
      - LoanStatus IN ('AVAILABLE', 'ON_LOAN', 'LOST')
`;
