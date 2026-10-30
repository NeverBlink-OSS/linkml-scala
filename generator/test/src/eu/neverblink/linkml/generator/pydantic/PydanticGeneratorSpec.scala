package eu.neverblink.linkml.generator.pydantic

import eu.neverblink.linkml.generator.options.{PruningMode, PydanticGeneratorOptions}
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PydanticGeneratorSpec extends AnyWordSpec, Matchers {
  def load(classesYaml: String): SchemaView =
    SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(
        s"""id: https://neverblink.eu/linkml/pydantic/test/
           |name: test
           |prefixes:
           |  ex: https://neverblink.eu/linkml/pydantic/test/
           |default_prefix: ex
           |imports:
           |  - linkml:types
           |$classesYaml""".stripMargin,
      ),
    )

  def generate(
      classesYaml: String,
      options: PydanticGeneratorOptions = PydanticGeneratorOptions(),
  ): String =
    PydanticGenerator(using load(classesYaml)).serialize(options)

  /** Python with `'''` for `"""`, which a Scala multi-line string can't have. */
  def py(text: String): String = text.replace("'''", "\"\"\"")

  "PydanticGenerator" should {
    "give every function of a catalogue model a method on one Protocol" in {
      ModelCatalogue.all.filter(_.model.root.functions.nonEmpty).foreach { entry =>
        withClue(s"${entry.name}: ") {
          val code = PydanticGenerator(using entry.model).serialize()
          val name = PydanticRenamer.functionsProtocolName(entry.model)
          code should include(s"class $name(Protocol):")
          entry.model.root.functions.keys.foreach { fn =>
            val fv = entry.model.functions(fn)
            code should include(s"    def ${PydanticRenamer.functionName(fv)}(")
            fv.errors.foreach { cls =>
              val error = PydanticRenamer.errorName(cls)
              code should include(s"class $error(Exception):")
              code should include(s":raises $error:")
            }
          }
        }
      }
    }

    "raise the errors of a function rather than returning them" in {
      val code = PydanticGenerator(using ModelCatalogue.functions.returns.model).serialize()
      code should include("class ClosedError(Exception):")
      code should include("    def __init__(self, error: Closed) -> None:")
      code should include("    def fetch(self, id: str) -> Item:")
      code should include(":raises ClosedError: The store is closed.")
      code should include("    def status(self) -> Status:") // an enum
      val basic = PydanticGenerator(using ModelCatalogue.functions.basic.model).serialize()
      basic should include("    def reindex(self) -> None:")
      basic should include("    def remove(self, title: str) -> None:")
    }

    "give a function the slots of its parameter class as arguments" in {
      val code = PydanticGenerator(using ModelCatalogue.functions.parameters.model).serialize()
      Seq(
        "        q: str,",
        "        tags: list[str] | None = None,",
        "        sort_by: SortOrder | None = None,",
        "    ) -> SearchResult:",
        ":param q: Required text query.",
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
      // A parameter without a default cannot follow one with a default, so `first` gets none
      code should include("def go(self, first: str | None, second: str) -> Result:")
    }

    "generate no functions Protocol for a schema without functions" in {
      PydanticGenerator(using ModelCatalogue.basic.model).serialize() should
        not include "(Protocol):"
    }

    "map slot ranges to Python types" in {
      val py = generate(
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
          |      at: {range: time}
          |      u: {range: uri}
          |      uc: {range: uriorcurie}
          |      many: {range: string, multivalued: true}
          |      whatever: {range: Anything}
          |      title: {range: langstring}
          |      keywords: {range: langstring, multivalued: true}
          |""".stripMargin,
      )
      py should include("class C(LinkMLModel):\n")
      py should include("    s: str\n")
      py should include("    i: StrictInt | None = None\n")
      py should include("    f: StrictFloat | None = None\n")
      py should include("    d: JsonDecimal | None = None\n")
      py should include("    b: StrictBool | None = None\n")
      py should include("    day: JsonDate | None = None\n")
      py should include("    when: JsonDateTime | None = None\n")
      py should include("    at: JsonTime | None = None\n")
      py should include("    u: str | None = None\n")
      py should include("    uc: str | None = None\n")
      py should include("    many: list[str] | None = None\n")
      py should include("    whatever: Any = None\n")
      py should include(
        "    title: str | Annotated[dict[str, str], Field(min_length=1)] | None = None\n",
      )
      py should include(
        "    keywords: list[str | Annotated[dict[str, str], Field(min_length=1)]] | None = None\n",
      )
      py should not include "class Anything"
      py should not include "_with_keys"
    }

    "import only what the module uses" in {
      val py = generate(
        """classes:
          |  C:
          |    attributes:
          |      s: {range: string}
          |""".stripMargin,
      )
      py should include("from typing import Any\n")
      py should include("from pydantic import BaseModel, ConfigDict, model_serializer\n")
      py should not include "import re"
      py should not include "datetime"
      py should not include "Decimal"
      py should not include "Enum"
    }

    "put constraints on the element type, and cardinality on the list" in {
      val py = generate(
        """types:
          |  Code:
          |    typeof: string
          |    pattern: "^[A-Z]+$"
          |classes:
          |  C:
          |    attributes:
          |      age: {range: integer, minimum_value: 0, maximum_value: 200}
          |      codes: {range: Code, multivalued: true, minimum_cardinality: 1, maximum_cardinality: 3}
          |      quote: {range: string, pattern: 'say "hi"'}
          |      price: {range: decimal, minimum_value: 0.5}
          |      day: {range: date, pattern: "^2"}
          |      code: {range: string, minimum_length: 2, maximum_length: 5}
          |      title: {range: langstring, maximum_length: 5}
          |""".stripMargin,
      )
      py should include(
        "    title: Annotated[str, Field(max_length=5)] | Annotated[dict[str, " +
          "Annotated[str, Field(max_length=5)]], Field(min_length=1)] | None = None\n",
      )
      py should include(
        "    code: Annotated[str, Field(min_length=2, max_length=5)] | None = None\n",
      )
      py should include("    age: Annotated[StrictInt, Field(ge=0, le=200)] | None = None\n")
      py should include(
        """    codes: list[Annotated[str, Field(pattern=r"^[A-Z]+$")]] | None = Field(None, """ +
          "min_length=1, max_length=3)\n",
      )
      py should include("""    quote: Annotated[str, Field(pattern="say \"hi\"")] | None = None""")
      py should include("    price: Annotated[JsonDecimal, Field(ge=0.5)] | None = None\n")
      // Pydantic has no patterns for dates
      py should include("    day: JsonDate | None = None\n")
    }

    "use the JSON keys, aliasing the ones that are not usable Python names" in {
      val py = generate(
        """classes:
          |  C:
          |    attributes:
          |      plain: {range: string}
          |      my-slot: {range: string, required: true}
          |      class: {range: string}
          |      1st: {range: string}
          |      _private: {range: string}
          |      json: {range: string}
          |      model_config: {range: string}
          |      date: {range: string}
          |      aliased: {range: string, alias: nice-name}
          |      d: {range: D}
          |  D:
          |    attributes:
          |      x: {range: string}
          |""".stripMargin,
      )
      py should include("    plain: str | None = None\n")
      py should include("""    my_slot: str = Field(alias="my-slot")""")
      py should include("""    class_: str | None = Field(None, alias="class")""")
      py should include("""    field_1st: str | None = Field(None, alias="1st")""")
      py should include("""    private: str | None = Field(None, alias="_private")""")
      py should include("""    json_: str | None = Field(None, alias="json")""")
      py should include("""    model_config_: str | None = Field(None, alias="model_config")""")
      // A field named like a type would hide the type in the class's annotations
      py should include("""    date_: str | None = Field(None, alias="date")""")
      py should include("""    nice_name: str | None = Field(None, alias="nice-name")""")
      py should include("    d: D | None = None\n")
    }

    "generate enums with members named after their values" in {
      val py = generate(
        """enums:
          |  Status:
          |    description: The status.
          |    permissible_values:
          |      active:
          |        description: In use.
          |      in-active:
          |      1st:
          |      None:
          |      mro:
          |  Dynamic:
          |    reachable_from:
          |      source_ontology: obo:ncbitaxon
          |classes:
          |  C:
          |    attributes:
          |      status: {range: Status}
          |      statuses: {range: Status, multivalued: true}
          |      taxon: {range: Dynamic}
          |""".stripMargin,
      )
      py should include(
        this.py(
          """class Status(str, Enum):
            |    '''The status.'''
            |
            |    active = "active"
            |    '''In use.'''
            |    in_active = "in-active"
            |    v1st = "1st"
            |    None_ = "None"
            |    mro_ = "mro"
            |""".stripMargin,
        ),
      )
      py should include("    status: Status | None = None\n")
      py should include("    statuses: list[Status] | None = None\n")
      py should include("    taxon: str | None = None\n")
      py should not include "class Dynamic"
    }

    "generate every inline form" in {
      val py = generate(
        """classes:
          |  Container:
          |    attributes:
          |      one: {range: Person, inlined: true, required: true}
          |      maybe: {range: Person, inlined: true}
          |      list: {range: Person, multivalued: true, inlined_as_list: true}
          |      dict: {range: Person, multivalued: true, inlined: true}
          |      labels: {range: Label, multivalued: true, inlined: true}
          |      numbered: {range: Numbered, multivalued: true, inlined: true}
          |      ref: {range: Person}
          |      refs: {range: Person, multivalued: true}
          |  Person:
          |    attributes:
          |      id: {identifier: true}
          |      name: {range: string}
          |      age: {range: integer}
          |  Label:
          |    attributes:
          |      key: {key: true}
          |      text: {range: string}
          |  Numbered:
          |    attributes:
          |      n: {identifier: true, range: integer}
          |      a: {range: string}
          |      b: {range: string}
          |""".stripMargin,
      )
      py should include("    one: Person\n")
      py should include("    maybe: Person | None = None\n")
      py should include("    list_: list[Person] | None = Field(None, alias=\"list\")\n")
      py should include(
        """    dict_: Annotated[dict[str, Person], _with_keys("id"), _without_keys("id")] | None """ +
          """= Field(None, alias="dict")""",
      )
      py should include(
        """    labels: Annotated[dict[str, Label], _with_keys("key", "text"), """ +
          """_without_keys("key", "text")] | None = None""",
      )
      py should include(
        """    numbered: Annotated[dict[str, Numbered], _with_keys("n", None, int), """ +
          """_without_keys("n")] | None = None""",
      )
      py should include("    ref: str | None = None\n    \"\"\"Reference to Person\"\"\"\n")
      py should include("    refs: list[str] | None = None\n    \"\"\"Reference to Person\"\"\"\n")
      py should include("def _with_keys(")
      py should include("def _without_keys(")
    }

    "generate type designators as literals and discriminated unions" in {
      val py = generate(
        """classes:
          |  Container:
          |    tree_root: true
          |    attributes:
          |      shapes: {range: Shape, multivalued: true, inlined_as_list: true}
          |      things: {range: Thing, multivalued: true, inlined_as_list: true}
          |      lonely: {range: Lonely, inlined: true}
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
          |      type: {range: uriorcurie, designates_type: true}
          |  SubThing:
          |    is_a: Thing
          |  Lonely:
          |    abstract: true
          |    attributes:
          |      kind: {range: string, designates_type: true, required: true}
          |  OnlyChild:
          |    is_a: Lonely
          |""".stripMargin,
      )
      py should include("class Shape(LinkMLModel):\n    kind: str\n")
      py should include(
        "class Circle(Shape):\n    kind: Literal[\"Circle\"] = \"Circle\"\n    radius: StrictInt\n",
      )
      py should include("    shapes: list[AnyShape] | None = None\n")
      py should include(
        this.py(
          """AnyShape = Annotated[
            |    Union[Circle, Square],
            |    Field(discriminator="kind"),
            |]
            |'''Any subclass of Shape, told apart by `kind`.'''
            |""".stripMargin,
        ),
      )
      // A concrete class with subclasses is one of its own union's members
      py should include("    things: list[AnyThing] | None = None\n")
      py should include("AnyThing = Annotated[\n    Union[SubThing, Thing],\n")
      py should include(
        "    type: Literal[\"https://neverblink.eu/linkml/pydantic/test/Thing\", \"ex:Thing\"] = " +
          "\"ex:Thing\"\n",
      )
      // With one member, no union
      py should include("    lonely: OnlyChild | None = None\n")
      py should not include "AnyLonely"
    }

    "inherit from is_a and the mixins, leaving out the ones another parent has" in {
      val py = generate(
        """classes:
          |  Named:
          |    mixin: true
          |    attributes:
          |      name: {range: string}
          |  Dated:
          |    mixin: true
          |    attributes:
          |      day: {range: date}
          |  Thing:
          |    mixins: [Named]
          |  Event:
          |    is_a: Thing
          |    mixins: [Dated, Named]
          |""".stripMargin,
      )
      py should include("class Event(Thing, Dated):\n")
      py should include("class Thing(Named):\n")
      // Parents come first
      py.indexOf("class Named(") should be < py.indexOf("class Thing(")
      py.indexOf("class Thing(") should be < py.indexOf("class Event(")
    }

    "fail on mixins that Python can't order" in {
      val ex = intercept[IllegalArgumentException] {
        generate(
          """classes:
            |  A:
            |    mixin: true
            |  B:
            |    mixin: true
            |  X:
            |    mixins: [A, B]
            |  Y:
            |    mixins: [B, A]
            |  Z:
            |    mixins: [X, Y]
            |""".stripMargin,
        )
      }.getMessage
      ex should include("class 'Z'")
    }

    "prune with each pruning mode, keeping the parents" when {
      val schema =
        """classes:
          |  Container:
          |    tree_root: true
          |    attributes:
          |      things: {range: Special, multivalued: true, inlined_as_list: true}
          |  Base:
          |    attributes:
          |      x: {range: integer}
          |  Special:
          |    is_a: Base
          |  Unrelated:
          |    attributes:
          |      x: {range: integer}
          |enums:
          |  Unused:
          |    permissible_values:
          |      a:
          |""".stripMargin
      def generateWith(mode: PruningMode) =
        generate(schema, PydanticGeneratorOptions(pruningMode = mode))

      "skip, by default: everything" in {
        val py = generate(schema)
        py should include("class Unrelated(")
        py should include("class Unused(")
      }

      "treeRoot: what the tree root reaches, and their parents" in {
        val py = generateWith(PruningMode.TreeRoot)
        py should include("class Container(")
        py should include("class Special(Base):")
        py should include("class Base(")
        py should not include "Unrelated"
        py should not include "Unused"
      }
    }

    "allow nulls and additional properties when asked" in {
      val schema =
        """classes:
          |  C:
          |    attributes:
          |      a: {range: string}
          |      b: {range: string, required: true}
          |""".stripMargin
      val default = generate(schema)
      default should include("extra=\"forbid\"")
      default should include("def _skip_none(")
      val py = generate(schema, PydanticGeneratorOptions(includeNull = true, open = true))
      py should include("extra=\"allow\"")
      py should include("def _skip_unset(")
      py should not include "_skip_none"
      py should include("    a: str | None = None\n    b: str\n")
    }

    "allow extra properties for classes whose extra_slots allows them" in {
      val schema =
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
          |""".stripMargin
      val py = generate(schema)
      def cls(name: String): String = raw"(?s)\nclass $name\(.*?\n\n".r.findFirstIn(py).get

      val allow = "    model_config = ConfigDict(extra=\"allow\")\n"
      cls("Open") should include(allow)
      cls("Typed") should include(allow)
      cls("ClosedTyped") should not include "model_config"
      // extra_slots is not inherited, but pydantic's config is
      cls("OpenChild") should include("    model_config = ConfigDict(extra=\"forbid\")\n")
      cls("Plain") should not include "model_config"
      // With --open, every class allows extra properties already
      generate(schema, PydanticGeneratorOptions(open = true)) should not include allow
    }

    "generate a document class for a tree root that is not a single object" in {
      def schema(form: String) =
        s"""classes:
           |  Item:
           |    tree_root: true
           |    extensions:
           |      tree_root_as: $form
           |    attributes:
           |      id: {identifier: true}
           |      name: {range: string}
           |      count: {range: integer}
           |""".stripMargin
      generate(schema("plain")) should not include "ItemDocument"
      generate(schema("optional")) should include(
        "class ItemDocument(RootModel[Item | None]):",
      )
      generate(schema("list")) should include(
        "class ItemDocument(RootModel[list[Item]]):",
      )
      generate(schema("compact_dict")) should include(
        """class ItemDocument(RootModel[Annotated[dict[str, Item], _with_keys("id"), """ +
          """_without_keys("id")]]):""",
      )
    }

    "write titles and descriptions as docstrings" in {
      val py = generate(
        this.py(
          """classes:
            |  C:
            |    title: A thing
            |    description: |-
            |      Two lines,
            |      with a \ and ''' in them.
            |    attributes:
            |      a:
            |        range: string
            |        description: Ends with a "quote"
            |""".stripMargin,
        ),
      )
      py should include(
        this.py(
          """class C(LinkMLModel):
            |    '''
            |    A thing: Two lines,
            |    with a \\ and \"\"\" in them.
            |    '''
            |""".stripMargin,
        ),
      )
      py should include(this.py("    a: str | None = None\n    '''Ends with a \"quote\\\"'''\n"))
    }

    "fall back to another language in docstrings" in {
      val py = generate(
        """classes:
          |  C:
          |    title: {pl: Klasa, de: Klasse}
          |    description: {pl: Opis., en: A description.}
          |""".stripMargin,
        PydanticGeneratorOptions(metadataLanguage = "fr"),
      )
      py should include("class C(LinkMLModel):\n    \"\"\"Klasse: A description.\"\"\"\n")
    }

    "prefix class names that are not valid or are reserved" in {
      val py = generate(
        """classes:
          |  Decimal:
          |    attributes:
          |      a: {range: string}
          |  3d:
          |    attributes:
          |      a: {range: string}
          |  None:
          |    attributes:
          |      a: {range: string}
          |""".stripMargin,
      )
      py should include("class _Decimal(LinkMLModel):")
      py should include("class _3D(LinkMLModel):")
      py should include("class _None(LinkMLModel):")
    }

    "fail on clashing names" when {
      "two classes" in {
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

      "two slots" in {
        val ex = intercept[IllegalArgumentException] {
          generate(
            """classes:
              |  C:
              |    attributes:
              |      my-slot: {range: string}
              |      my_slot: {range: string}
              |""".stripMargin,
          )
        }.getMessage
        ex should include("'my-slot'")
        ex should include("'my_slot'")
      }

      "two enum values" in {
        val ex = intercept[IllegalArgumentException] {
          generate(
            """enums:
              |  E:
              |    permissible_values:
              |      a-b:
              |      a_b:
              |""".stripMargin,
          )
        }.getMessage
        ex should include("'a-b'")
      }
    }

    "map union_of types to unions of their members' types" in {
      val py = generate(
        """types:
          |  StringOrInteger:
          |    union_of: [string, integer]
          |  Count:
          |    typeof: integer
          |    minimum_value: 0
          |  Code:
          |    typeof: string
          |    pattern: "^[A-Z]+$"
          |  CodeOrCount:
          |    union_of: [Code, Count]
          |  Numbers:
          |    union_of: [integer, Count, float]
          |  Choice:
          |    union_of: [boolean, StringOrInteger, string]
          |  Text:
          |    union_of: [string, uri]
          |classes:
          |  C:
          |    attributes:
          |      value: {range: StringOrInteger, required: true}
          |      many: {range: StringOrInteger, multivalued: true}
          |      constrained: {range: CodeOrCount}
          |      number: {range: Numbers}
          |      choice: {range: Choice}
          |      text: {range: Text}
          |""".stripMargin,
      )
      py should include("    value: str | StrictInt\n")
      py should include("    many: list[str | StrictInt] | None = None\n")
      // Each member keeps its own constraints
      py should include(
        """    constrained: Annotated[str, Field(pattern=r"^[A-Z]+$")] | """ +
          "Annotated[StrictInt, Field(ge=0)] | None = None\n",
      )
      py should include(
        "    number: StrictInt | Annotated[StrictInt, Field(ge=0)] | StrictFloat | None",
      )
      // Members that map to the same Python type appear once
      py should include("    text: str | None = None\n")
      // Nested unions in their declared place
      py should include("    choice: StrictBool | str | StrictInt | None = None\n")
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.allOptIn do
        s"model '${entry.name}'" in {
          PydanticGenerator(using entry.model).serialize() should not be empty
        }
    }
  }
}
