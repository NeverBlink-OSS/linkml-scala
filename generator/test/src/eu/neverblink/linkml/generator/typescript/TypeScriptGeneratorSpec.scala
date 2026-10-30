package eu.neverblink.linkml.generator.typescript

import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TypeScriptGeneratorSpec extends AnyWordSpec, Matchers {
  def load(classesYaml: String): SchemaView =
    SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(
        s"""id: https://neverblink.eu/linkml/typescript/test/
           |name: test
           |prefixes:
           |  ex: https://neverblink.eu/linkml/typescript/test/
           |default_prefix: ex
           |imports:
           |  - linkml:types
           |$classesYaml""".stripMargin,
      ),
    )

  def generate(
      classesYaml: String,
      options: TypeScriptGenerator.Options = TypeScriptGenerator.Options(),
  ): String =
    TypeScriptGenerator(using load(classesYaml)).serialize(options)

  val resultMode: TypeScriptGenerator.Options =
    TypeScriptGenerator.Options(errorHandling = ErrorHandling.result)

  "TypeScriptGenerator" should {
    "give every function of a catalogue model a method on one interface" in {
      ModelCatalogue.all.filter(_.model.root.functions.nonEmpty).foreach { entry =>
        withClue(s"${entry.name}: ") {
          val code = TypeScriptGenerator(using entry.model).serialize()
          val name = TypeScriptRenamer.functionsInterfaceName(entry.model)
          code should include(s"export interface $name {")
          entry.model.root.functions.keys.foreach { fn =>
            code should include(s"  ${TypeScriptRenamer.functionName(entry.model.functions(fn))}(")
          }
        }
      }
    }

    "return what a function gives back, and document what it throws" in {
      val code = TypeScriptGenerator(using ModelCatalogue.functions.returns.model).serialize()
      code should include("fetch(id: string): Item;")
      code should include("@throws {Unavailable} The item is not in the store.")
      code should include("@throws {Closed} The store is closed.")
      code should include("listItems(): ItemList;")
      code should include("status(): Status;") // an enum
      code should include("totalValue(): number;") // a type
      code should not include "Result<"
      val basic = TypeScriptGenerator(using ModelCatalogue.functions.basic.model).serialize()
      basic should include("reindex(): void;")
      basic should include("remove(title: string): void;")
      basic should include("countBooks(): number;")
    }

    "return a tagged Result when asked" in {
      val code = TypeScriptGenerator(using ModelCatalogue.functions.returns.model)
        .serialize(resultMode)
      code should include(
        "fetch(id: string): " +
          """Result<Item, Failure<"Unavailable", Unavailable> | Failure<"Closed", Closed>>;""",
      )
      code should include("""listItems(): Result<ItemList, Failure<"Closed", Closed>>;""")
      code should include("status(): Status;")
      code should include("export type Result<T, E> =")
      code should include("export type Failure<K extends string, T> = { kind: K; value: T };")
      code should not include "@throws"
    }

    "wrap a function that returns nothing but can fail" in {
      val code = TypeScriptGenerator(using ModelCatalogue.functions.basic.model)
        .serialize(resultMode)
      code should include("""remove(title: string): Result<void, Failure<"NotFound", NotFound>>;""")
      code should include("reindex(): void;")
    }

    "leave out the Result helpers when no function has errors" in {
      val code = TypeScriptGenerator(using ModelCatalogue.functions.parameters.model)
        .serialize(resultMode)
      code should include("): SearchResult;")
      code should not include "export type Result<"
    }

    "refuse a class that takes the name of a Result helper" in {
      val thrown = intercept[IllegalArgumentException] {
        generate(
          """classes:
            |  Failure:
            |  Book:
            |functions:
            |  lookup:
            |    returns: Book
            |    errors:
            |      - Failure
            |""".stripMargin,
          resultMode,
        )
      }
      thrown.getMessage should include("the Failure helper type")
    }

    "mark the optional parameters at the end of the list with ?" in {
      val code = TypeScriptGenerator(using ModelCatalogue.functions.parameters.model).serialize()
      Seq(
        "q: string,",
        "tags?: string[],",
        "page?: number,",
        "sortBy?: SortOrder,",
        "): SearchResult;",
        "@param q Required text query.",
      ).foreach(snippet => code should include(snippet))
    }

    "keep the place of an optional parameter that a required one follows" in {
      val code = generate(
        """classes:
          |  Request:
          |    attributes:
          |      first:
          |        range: string
          |      second:
          |        range: string
          |        required: true
          |  Result:
          |functions:
          |  go:
          |    parameters: Request
          |    returns: Result
          |""".stripMargin,
      )
      // A required parameter cannot follow an optional one, so `first` takes undefined instead
      code should include("first: string | undefined,")
      code should include("second: string,")
    }

    "generate no functions interface for a schema without functions" in {
      TypeScriptGenerator(using ModelCatalogue.basic.model).serialize() should
        not include "Functions {"
    }

    "map slot ranges to TypeScript types" in {
      val ts = generate(
        """classes:
          |  Anything:
          |    class_uri: linkml:Any
          |  C:
          |    attributes:
          |      s: {range: string, required: true}
          |      i: {range: integer}
          |      f: {range: float}
          |      d: {range: decimal}
          |      b: {range: boolean}
          |      day: {range: date}
          |      when: {range: datetime}
          |      u: {range: uri}
          |      uc: {range: uriorcurie}
          |      many: {range: string, multivalued: true}
          |      whatever: {range: Anything}
          |      title: {range: langstring}
          |      keywords: {range: langstring, multivalued: true}
          |""".stripMargin,
      )
      ts should include("export interface C {\n")
      ts should include("  s: string;\n")
      ts should include("  i?: number;\n")
      ts should include("  f?: number;\n")
      ts should include("  d?: number;\n")
      ts should include("  b?: boolean;\n")
      ts should include("  day?: string;\n")
      ts should include("  when?: string;\n")
      ts should include("  u?: string;\n")
      ts should include("  uc?: string;\n")
      ts should include("  many?: string[];\n")
      ts should include("  whatever?: unknown;\n")
      ts should include("  title?: string | Record<string, string>;\n")
      ts should include("  keywords?: (string | Record<string, string>)[];\n")
      ts should not include "interface Anything"
      ts should not include "KeyOptional"
    }

    "map union_of types to unions of their members' types" in {
      val ts = generate(
        """types:
          |  StringOrInteger:
          |    union_of: [string, integer]
          |  Count:
          |    typeof: integer
          |  Numbers:
          |    union_of: [integer, Count, float]
          |classes:
          |  C:
          |    attributes:
          |      value: {range: StringOrInteger, required: true}
          |      many: {range: StringOrInteger, multivalued: true}
          |      number: {range: Numbers}
          |""".stripMargin,
      )
      ts should include("  value: string | number;\n")
      ts should include("  many?: (string | number)[];\n")
      ts should include("  number?: number;\n")
    }

    "keep the declared order of union members, with nested unions in place" in {
      val ts = generate(
        """types:
          |  StringOrInteger:
          |    union_of: [string, integer]
          |  Choice:
          |    union_of: [boolean, StringOrInteger, string]
          |  Reversed:
          |    union_of: [integer, string]
          |classes:
          |  C:
          |    attributes:
          |      choice: {range: Choice}
          |      reversed: {range: Reversed}
          |""".stripMargin,
      )
      ts should include("  choice?: boolean | string | number;\n")
      ts should include("  reversed?: number | string;\n")
    }

    "use the same JSON keys as the JSON Schema, quoting them where needed" in {
      val ts = generate(
        """classes:
          |  C:
          |    attributes:
          |      my-slot: {range: string}
          |      renamed:
          |        alias: other_name
          |        range: string
          |      odd:
          |        alias: odd-name
          |        range: string
          |""".stripMargin,
      )
      ts should include("  \"my-slot\"?: string;")
      ts should include("  other_name?: string;")
      ts should include("""  "odd-name"?: string;""")
      ts should not include "renamed"
    }

    "generate enums as unions of string literals" in {
      val ts = generate(
        """classes:
          |  C:
          |    attributes:
          |      status: {range: Status, required: true}
          |      statuses: {range: Status, multivalued: true}
          |      dynamic: {range: Dynamic}
          |enums:
          |  Status:
          |    permissible_values:
          |      active:
          |        alias: active
          |      in_active:
          |        alias: in-active
          |      say_hi:
          |        alias: 'say "hi"'
          |  Dynamic:
          |    reachable_from:
          |      source_ontology: obo:ncbitaxon
          |      source_nodes:
          |        - NCBITaxon:9606
          |""".stripMargin,
      )
      ts should include("  status: Status;\n")
      ts should include("  statuses?: Status[];\n")
      ts should include(
        "export type Status =\n  | \"active\"\n  | \"in-active\"\n  | \"say \\\"hi\\\"\";\n",
      )
      ts should include("export type Dynamic = string;\n")
    }

    "generate every inline form" in {
      val ts = generate(
        """classes:
          |  Container:
          |    attributes:
          |      as_list: {range: Person, multivalued: true, inlined_as_list: true}
          |      as_compact_dict: {range: Person, multivalued: true, inlined: true}
          |      as_simple_dict: {range: Tag, multivalued: true, inlined: true}
          |      single: {range: Person, inlined: true}
          |      ref: {range: Person}
          |      refs: {range: Person, multivalued: true}
          |  Person:
          |    attributes:
          |      id: {identifier: true}
          |      name: {range: string}
          |      age: {range: integer}
          |  Tag:
          |    attributes:
          |      key: {key: true}
          |      label: {range: string}
          |""".stripMargin,
      )
      ts should include("  as_list?: Person[];\n")
      ts should include("  as_compact_dict?: Record<string, KeyOptional<Person, \"id\">>;\n")
      ts should include(
        "  as_simple_dict?: Record<string, string | KeyOptional<Tag, \"key\">>;\n",
      )
      ts should include("  single?: Person;\n")
      ts should include("  /** Reference to Person */\n  ref?: string;\n")
      ts should include("  /** Reference to Person */\n  refs?: string[];\n")
      ts should include("export type KeyOptional<T, K extends keyof T>")
    }

    "generate type designators as discriminated unions" in {
      val ts = generate(
        """classes:
          |  Container:
          |    tree_root: true
          |    attributes:
          |      shapes: {range: Shape, multivalued: true, inlined_as_list: true}
          |      things: {range: Thing, multivalued: true, inlined_as_list: true}
          |  Shape:
          |    abstract: true
          |    attributes:
          |      kind: {range: string, designates_type: true, required: true}
          |  Circle:
          |    is_a: Shape
          |    attributes:
          |      radius: {range: integer, required: true}
          |  Square:
          |    is_a: Shape
          |  Thing:
          |    attributes:
          |      type: {range: uri, designates_type: true}
          |  SubThing:
          |    is_a: Thing
          |""".stripMargin,
      )
      // abstract class: the union takes its name
      ts should include("  shapes?: Shape[];\n")
      ts should include("export type Shape =\n  | Circle\n  | Square;\n")
      ts should not include "interface Shape"
      ts should include("export interface Circle {\n  kind: \"Circle\";\n  radius: number;\n}\n")
      ts should include("export interface Square {\n  kind: \"Square\";\n}\n")
      // concrete class with subclasses: interface plus an AnyX union
      ts should include("  things?: AnyThing[];\n")
      ts should include("export type AnyThing =\n  | SubThing\n  | Thing;\n")
      ts should include("  type?: \"https://neverblink.eu/linkml/typescript/test/Thing\";\n")
      ts should include("  type?: \"https://neverblink.eu/linkml/typescript/test/SubThing\";\n")
    }

    "use both the URI and the CURIE for uriorcurie designators" in {
      val ts = generate(
        """classes:
          |  Thing:
          |    attributes:
          |      type: {range: uriorcurie, designates_type: true, required: true}
          |      label: {range: curie}
          |""".stripMargin,
      )
      ts should include(
        "  type: \"https://neverblink.eu/linkml/typescript/test/Thing\" | \"ex:Thing\";\n",
      )
    }

    "prune with each pruning mode" when {
      val schema =
        """classes:
          |  Container:
          |    tree_root: true
          |    attributes:
          |      shapes: {range: Shape, multivalued: true, inlined_as_list: true}
          |  Shape:
          |    abstract: true
          |    attributes:
          |      kind: {range: string, designates_type: true, required: true}
          |  Circle:
          |    is_a: Shape
          |    attributes:
          |      centre: {range: Point, required: true, inlined: true}
          |  Point:
          |    attributes:
          |      x: {range: integer}
          |  Unrelated:
          |    attributes:
          |      x: {range: integer}
          |enums:
          |  Unused:
          |    permissible_values:
          |      a:
          |""".stripMargin
      def generateWith(mode: PruningMode) = generate(schema, TypeScriptGenerator.Options(mode))

      "skip, by default: everything" in {
        val ts = generate(schema)
        ts should include("export interface Unrelated")
        ts should include("export type Unused")
      }

      "treeRoot: what the tree root reaches, including the members of designated unions" in {
        val ts = generateWith(PruningMode.treeRoot(None))
        ts should include("export interface Container")
        ts should include("export type Shape =")
        ts should include("export interface Circle")
        ts should include("export interface Point")
        ts should not include "Unrelated"
        ts should not include "Unused"
      }

      "treeRoot with an override" in {
        val ts = generateWith(PruningMode.treeRoot(Some("Point")))
        ts should include("export interface Point")
        ts should not include "Container"
        ts should not include "Circle"
      }

      "schema: everything defined and used in the root schema" in {
        val ts = generateWith(PruningMode.schemaRoot)
        ts should include("export interface Unrelated")
        ts should include("export interface Circle")
        ts should include("export type Unused")
      }
    }

    "allow nulls and additional properties when asked" in {
      val ts = generate(
        """classes:
          |  C:
          |    attributes:
          |      a: {range: string}
          |      b: {range: string, required: true}
          |      d: {range: Keyed, multivalued: true, inlined: true}
          |  Keyed:
          |    attributes:
          |      id: {identifier: true}
          |      x: {range: string}
          |      y: {range: string}
          |""".stripMargin,
        TypeScriptGenerator.Options(includeNull = true, open = true),
      )
      ts should include("  a?: string | null;\n")
      ts should include("  b: string;\n")
      ts should include("  d?: Record<string, KeyOptional<Keyed, \"id\"> | null> | null;\n")
      ts should include("  [key: string]: unknown;\n}\n")
    }

    "allow additional properties for classes whose extra_slots allows them" in {
      val ts = generate(
        """classes:
          |  Open:
          |    extra_slots:
          |      allowed: true
          |    attributes:
          |      a: {}
          |  Typed:
          |    extra_slots:
          |      range_expression:
          |        range: string
          |    attributes:
          |      a: {}
          |  ClosedTyped:
          |    extra_slots:
          |      allowed: false
          |      range_expression:
          |        range: string
          |    attributes:
          |      a: {}
          |  OpenChild:
          |    is_a: Open
          |  Plain:
          |    attributes:
          |      a: {}
          |""".stripMargin,
      )
      def interface(name: String): String =
        raw"(?s)export interface $name \{.*?\n\}".r.findFirstIn(ts).get

      interface("Open") should include("  [key: string]: unknown;\n")
      interface("Typed") should include("  [key: string]: unknown;\n")
      interface("ClosedTyped") should not include "[key: string]"
      interface("OpenChild") should not include "[key: string]"
      interface("Plain") should not include "[key: string]"
    }

    "write titles and descriptions as doc comments" in {
      val ts = generate(
        """classes:
          |  C:
          |    title: The class
          |    description: |-
          |      First line.
          |      Has a */ in it.
          |    attributes:
          |      a:
          |        title: Slot A
          |      b:
          |        description: Only a description.
          |""".stripMargin,
      )
      ts should include(
        "/**\n * The class: First line.\n * Has a *\\/ in it.\n */\nexport interface C",
      )
      ts should include("  /** Slot A */\n  a?: string;\n")
      ts should include("  /** Only a description. */\n  b?: string;\n")
    }

    "fall back to another language in doc comments" in {
      val ts = generate(
        """classes:
          |  C:
          |    title: {pl: Klasa, de: Klasse}
          |    description: {pl: Opis., en: A description.}
          |""".stripMargin,
        TypeScriptGenerator.Options(metadataLanguage = "fr"),
      )
      ts should include("/** Klasse: A description. */\nexport interface C")
    }

    "split doc comments at every kind of line break" in {
      val ts = generate(
        """classes:
          |  C:
          |    description: "a\r\nb\rc\n\nd  \ne"
          |""".stripMargin,
      )
      ts should include("/**\n * a\n * b\n * c\n *\n * d\n * e\n */\nexport interface C")
    }

    "escape string literals" in {
      TypeScriptGenerator.stringLiteral("") shouldBe "\"\""
      TypeScriptGenerator.stringLiteral(
        "a\"b\\c\nd\re\tf\u0001g\u001fh i j\u007fk",
      ) shouldBe "\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\\u001fh\\u2028i\\u2029j\u007fk\""
    }

    "quote property keys that are not plain identifiers" in {
      for key <- Seq("a", "A_1", "_a", "$a", "a$b") do
        TypeScriptGenerator.propertyKey(key) shouldBe key
      for key <- Seq("", "1a", "a-b", "a b", "ä") do
        TypeScriptGenerator.propertyKey(key) shouldBe TypeScriptGenerator.stringLiteral(key)
    }

    "prefix names that are not valid or are reserved" in {
      val ts = generate(
        """classes:
          |  Record:
          |    attributes:
          |      a: {range: string}
          |  3d:
          |    attributes:
          |      a: {range: string}
          |""".stripMargin,
      )
      ts should include("export interface _Record {")
      ts should include("export interface _3D {")
    }

    "fail on clashing names" in {
      val ex = intercept[IllegalArgumentException] {
        generate(
          """classes:
            |  Thing:
            |    attributes:
            |      type: {range: string, designates_type: true}
            |  SubThing:
            |    is_a: Thing
            |  AnyThing:
            |    attributes:
            |      a: {range: string}
            |""".stripMargin,
        )
      }.getMessage
      ex should include("AnyThing")
      ex should include("Thing")
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.allOptIn do
        s"model '${entry.name}'" in {
          TypeScriptGenerator(using entry.model).serialize() should not be empty
        }
    }
  }
}
