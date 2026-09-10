package eu.neverblink.linkml.generator.ossie

/** The LinkML schemas for Ossie tests.
  *
  * Each body is spliced into a minimal schema by [[OssieFixtures.schemaOf]].
  */
object OssieCases {
  final case class Case(label: String, body: String)

  private def of(label: String)(body: String): Case = Case(label, body)

  val classWithDescription: Case = of("a described class")("""
    |classes:
    |  Book:
    |    description: A book.
    |    attributes:
    |      title: {}
    """)

  val enumeration: Case = of("an enum")("""
    |enums:
    |  Status:
    |    permissible_values:
    |      ALIVE: {}
    |      DEAD: {}
    |classes:
    |  Person:
    |    attributes:
    |      status:
    |        range: Status
    """)

  val enumValueWithApostrophe: Case = of("an enum value holding an apostrophe")("""
    |enums:
    |  Kind:
    |    permissible_values:
    |      "it's": {}
    |classes:
    |  Thing:
    |    attributes:
    |      kind:
    |        range: Kind
    """)

  val namedType: Case = of("a named type over a base")("""
    |types:
    |  SmallInt:
    |    base: int
    |    uri: xsd:integer
    |classes:
    |  Thing:
    |    attributes:
    |      n:
    |        range: SmallInt
    """)

  val typeofChain: Case = of("a named type reached through typeof")("""
    |types:
    |  PositiveInt:
    |    typeof: integer
    |    minimum_value: 1
    |classes:
    |  Thing:
    |    attributes:
    |      n:
    |        range: PositiveInt
    """)

  val snakeCasedClassName: Case = of("a snake_cased class name")("""
    |classes:
    |  order_line:
    |    attributes:
    |      x: {}
    """)

  val primitiveRanges: Case = of("every primitive range")("""
    |classes:
    |  Thing:
    |    attributes:
    |      s: { range: string }
    |      i: { range: integer }
    |      f: { range: float }
    |      d: { range: double }
    |      dec: { range: decimal }
    |      b: { range: boolean }
    |      dt: { range: date }
    |      dtt: { range: datetime }
    |      t: { range: time }
    |      u: { range: uri }
    |      c: { range: curie }
    """)

  val anyRange: Case = of("a linkml:Any range")("""
    |classes:
    |  Any:
    |    class_uri: linkml:Any
    |  Thing:
    |    attributes:
    |      whatever:
    |        range: Any
    """)

  val unknownBase: Case = of("a named type whose base is unknown")("""
    |types:
    |  Weird:
    |    base: NotABaseWeKnow
    |    uri: xsd:string
    |classes:
    |  Thing:
    |    attributes:
    |      x:
    |        range: Weird
    """)

  val classRanges: Case = of("class ranges, inlined and not")("""
    |classes:
    |  Author:
    |    attributes:
    |      id:
    |        identifier: true
    |  Book:
    |    attributes:
    |      by:
    |        range: Author
    |      inline_by:
    |        range: Author
    |        inlined: true
    """)

  val multiWordSlot: Case = of("a multi-word slot name")("""
    |classes:
    |  Person:
    |    attributes:
    |      full_name: {}
    """)

  val aliasedSlot: Case = of("an aliased slot")("""
    |classes:
    |  Person:
    |    attributes:
    |      full_name:
    |        alias: fullName
    """)

  val aliasedClass: Case = of("an aliased class")("""
    |classes:
    |  http_thing:
    |    alias: HTTPThing
    |    attributes:
    |      x: {}
    """)

  val selfReference: Case = of("a slot pointing at its own class")("""
    |classes:
    |  Person:
    |    attributes:
    |      id:
    |        identifier: true
    |      parent_of:
    |        range: Person
    |        multivalued: true
    """)

  val multivalued: Case = of("a multivalued slot next to a single-valued one")("""
    |classes:
    |  Person:
    |    attributes:
    |      one: {}
    |      many:
    |        multivalued: true
    """)

  val requiredSlot: Case = of("a required slot")("""
    |classes:
    |  Person:
    |    attributes:
    |      name:
    |        required: true
    |      nickname: {}
    """)

  val numericBounds: Case = of("numeric bounds on a slot")("""
    |classes:
    |  Thing:
    |    attributes:
    |      n:
    |        range: integer
    |        minimum_value: -1
    |        maximum_value: 10
    """)

  val pattern: Case = of("a pattern on a slot")("""
    |classes:
    |  Thing:
    |    attributes:
    |      code:
    |        pattern: '^[A-Z]{3}$'
    """)

  val patternWithApostrophe: Case = of("a pattern holding an apostrophe")("""
    |classes:
    |  Thing:
    |    attributes:
    |      code:
    |        pattern: "it's"
    """)

  val nonNumericBound: Case = of("a bound that is not a number")("""
    |classes:
    |  Thing:
    |    attributes:
    |      when:
    |        range: date
    |        minimum_value: 2020-01-01
    |      n:
    |        range: integer
    |        minimum_value: 5
    """)

  val quotedNumberBound: Case = of("a quoted number as a bound")("""
    |classes:
    |  Thing:
    |    attributes:
    |      n:
    |        range: integer
    |        minimum_value: "5"
    """)

  val typeConstraints: Case = of("constraints on a named type")("""
    |types:
    |  SmallInt:
    |    base: int
    |    uri: xsd:integer
    |    minimum_value: 1
    |classes:
    |  Thing:
    |    attributes:
    |      n:
    |        range: SmallInt
    """)

  val inheritance: Case = of("is_a and mixins together")("""
    |classes:
    |  Base:
    |    attributes:
    |      a: {}
    |  Mixed:
    |    mixin: true
    |    attributes:
    |      b: {}
    |  Sub:
    |    is_a: Base
    |    mixins: [Mixed]
    |    attributes:
    |      c: {}
    """)

  val inheritedUnchanged: Case = of("a slot a subtype inherits unchanged")("""
    |classes:
    |  Base:
    |    attributes:
    |      a: {}
    |  Sub:
    |    is_a: Base
    |    attributes:
    |      b: {}
    """)

  val narrowedRange: Case = of("a slot a subtype narrows the range of")("""
    |classes:
    |  Cat:
    |    attributes:
    |      id:
    |        identifier: true
    |  Base:
    |    attributes:
    |      pet: {}
    |  Sub:
    |    is_a: Base
    |    slot_usage:
    |      pet:
    |        range: Cat
    """)

  val narrowedRequired: Case = of("a slot a subtype only makes required")("""
    |classes:
    |  Base:
    |    attributes:
    |      name: {}
    |  Sub:
    |    is_a: Base
    |    slot_usage:
    |      name:
    |        required: true
    """)

  val identifierSlot: Case = of("an identifier slot")("""
    |classes:
    |  Person:
    |    attributes:
    |      id:
    |        identifier: true
    """)

  val keySlot: Case = of("a key slot")("""
    |classes:
    |  Person:
    |    attributes:
    |      code:
    |        key: true
    """)

  val compoundKey: Case = of("a compound unique key")("""
    |slots:
    |  order: {}
    |  nr:
    |    range: integer
    |classes:
    |  OrderLine:
    |    slots: [order, nr]
    |    unique_keys:
    |      line:
    |        unique_key_slots: [order, nr]
    """)

  val describedSchema: Case = of("a described schema")("""
    |description: A demo schema.
    |classes:
    |  Thing:
    |    attributes:
    |      x: {}
    """)

  val multilingualDescriptions: Case = of("descriptions in several languages")("""
    |classes:
    |  Book:
    |    description:
    |      en: A book.
    |      pl: Książka.
    |    attributes:
    |      title:
    |        description:
    |          en: Its title.
    |          pl: Jej tytuł.
    """)

  val aiContextObject: Case = of("an object-valued ai_context")("""
    |extensions:
    |  ai_context:
    |    value:
    |      instructions: Prefer the full name.
    |      synonyms: [human, individual]
    |classes:
    |  Thing:
    |    attributes:
    |      x: {}
    """)

  val aiContextString: Case = of("a string-valued ai_context")("""
    |extensions:
    |  ai_context: Answer questions about people.
    |classes:
    |  Thing:
    |    attributes:
    |      x: {}
    """)

  val plain: Case = of("a schema with nothing else on it")("""
    |classes:
    |  Thing:
    |    attributes:
    |      x: {}
    """)

  val treeRoot: Case = of("a tree root next to an unreachable class")("""
    |classes:
    |  Root:
    |    tree_root: true
    |    attributes:
    |      x: {}
    |  Orphan:
    |    attributes:
    |      y: {}
    """)

  val severalKinds: Case = of("classes, an enum and a self-reference together")("""
    |enums:
    |  Status:
    |    permissible_values:
    |      OK: {}
    |classes:
    |  Person:
    |    attributes:
    |      id:
    |        identifier: true
    |      status:
    |        range: Status
    |      friend:
    |        range: Person
    |        multivalued: true
    """)

  /** Every case, for the specs that walk all of them. */
  val all: Seq[Case] = Seq(
    classWithDescription,
    enumeration,
    enumValueWithApostrophe,
    namedType,
    typeofChain,
    snakeCasedClassName,
    primitiveRanges,
    anyRange,
    unknownBase,
    classRanges,
    multiWordSlot,
    aliasedSlot,
    aliasedClass,
    selfReference,
    multivalued,
    requiredSlot,
    numericBounds,
    pattern,
    patternWithApostrophe,
    nonNumericBound,
    quotedNumberBound,
    typeConstraints,
    inheritance,
    inheritedUnchanged,
    narrowedRange,
    narrowedRequired,
    identifierSlot,
    keySlot,
    compoundKey,
    describedSchema,
    multilingualDescriptions,
    aiContextObject,
    aiContextString,
    plain,
    treeRoot,
    severalKinds,
  )
}
