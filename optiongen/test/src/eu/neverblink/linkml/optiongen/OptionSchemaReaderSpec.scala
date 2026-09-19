package eu.neverblink.linkml.optiongen

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Inspectors.forEvery

import java.nio.file.{Files, Path}

class OptionSchemaReaderSpec extends AnyWordSpec, Matchers {

  private val fixture =
    """id: https://example.org/options
      |name: options
      |prefixes:
      |  ex: https://example.org/options#
      |  optgen: https://linkml.neverblink.eu/model/generator-options/annotations/
      |  linkml: https://w3id.org/linkml/
      |default_prefix: ex
      |imports: [linkml:types]
      |annotations:
      |  optgen:catalog:
      |    tag: optgen:catalog
      |    value: {version: 1}
      |types:
      |  Label:
      |    typeof: string
      |classes:
      |  ExampleOptions:
      |    description: Test options.
      |    annotations:
      |      optgen:generator:
      |        tag: optgen:generator
      |        value:
      |          id: example
      |          interfaces:
      |            native: {preset: core}
      |            python: {preset: core_python}
      |            js:
      |              parameters:
      |                - {selector: package, name: package, ts_name: packageName, default: {kind: none}}
      |                - {selector: flag, name: enabled, default: {kind: literal, value: false}}
      |                - {selector: optional, name: root, default: {kind: literal, value: null}}
      |              omitted: [count]
      |            cli:
      |              parameters:
      |                - {selector: flag, name: flag}
      |                - {selector: package, name: package}
      |                - {selector: count, name: count}
      |                - {selector: optional, name: optional}
      |              omitted: []
      |    attributes:
      |      package:
      |        range: Label
      |        required: true
      |        rank: 2
      |        ifabsent: 'string(a "quote")'
      |        description: Package label.
      |      flag:
      |        range: boolean
      |        required: true
      |        rank: 1
      |        ifabsent: 'True'
      |      count:
      |        range: integer
      |        required: true
      |        rank: 3
      |        ifabsent: int(0)
      |      optional:
      |        range: string
      |        required: false
      |        rank: 4
      |""".stripMargin

  private val ids = Set(
    "json_schema",
    "shacl",
    "rdfs",
    "linkml",
    "frictionless",
    "graphql",
    "er_diagram",
    "ossie",
    "scala",
    "translation",
  )

  private def catalog(result: Either[Vector[Diagnostic], Catalog]): Catalog = result match {
    case Right(value) => value
    case Left(errors) => fail(errors.mkString("\n"))
  }

  private def rejects(yaml: String, expected: String): Unit = {
    val errors = OptionSchemaReader.read(yaml, "invalid.yaml") match {
      case Left(value) => value
      case Right(_) => fail("invalid schema accepted")
    }
    errors.foreach(_.location should startWith("invalid.yaml/"))
    errors.map(_.message).mkString("\n").toLowerCase should include(expected.toLowerCase)
  }

  "OptionSchemaReader" should {
    "resolve ranked fields, inherited types, and explicit interface defaults" in {
      val result = catalog(OptionSchemaReader.read(fixture, "fixture.yaml", Set("example")))
      val generator = result.generators.head
      generator.id shouldBe "example"
      generator.fields.map(f => (f.name, f.valueType, f.default)) shouldBe Vector(
        ("flag", ValueType.Bool, Value.Bool(true)),
        ("package", ValueType.Text, Value.Text("a \"quote\"")),
        ("count", ValueType.Int32, Value.Int32(0)),
        ("optional", ValueType.OptionalText, Value.Absent),
      )
      val js = generator.profiles(Interface.Js)
      js.parameters.map(p => (p.name, p.default)) shouldBe Vector(
        ("package", None),
        ("enabled", Some(Value.Bool(false))),
        ("root", Some(Value.Absent)),
      )
      js.parameters.head.tsName shouldBe Some("packageName")
      js.parameters.head.description shouldBe "Package label."
      js.omitted shouldBe Set("count")
      generator.profiles(Interface.Native).parameters.map(_.name) shouldBe
        Vector("flag", "package", "count", "optional")
      generator.fields.forall(_.location.startsWith("fixture.yaml")) shouldBe true
    }

    "reject malformed metadata, defaults, and mappings with their location" in {
      val cases = Vector(
        ("version", fixture.replace("version: 1", "version: 2"), "version"),
        ("catalog key", fixture.replace("version: 1", "version: 1, typo: true"), "typo"),
        (
          "generator key",
          fixture.replace("id: example", "id: example\n          typo: true"),
          "typo",
        ),
        ("rank collision", fixture.replace("rank: 3", "rank: 2"), "rank"),
        ("nonpositive rank", fixture.replace("rank: 3", "rank: 0"), "rank"),
        ("missing rank", fixture.replace("        rank: 3\n", ""), "rank"),
        ("wrong default type", fixture.replace("int(0)", "string(zero)"), "ifabsent"),
        ("integer overflow", fixture.replace("int(0)", "int(2147483648)"), "ifabsent"),
        ("unsupported boolean", fixture.replace("'True'", "'TRUE'"), "ifabsent"),
        ("missing default", fixture.replace("        ifabsent: int(0)\n", ""), "default"),
        (
          "unresolved range",
          fixture.replace("range: integer", "range: MissingType"),
          "MissingType",
        ),
        ("unsupported range", fixture.replace("range: integer", "range: date"), "range"),
        (
          "multivalued",
          fixture.replace("rank: 3", "rank: 3\n        multivalued: true"),
          "multivalued",
        ),
        ("unknown selector", fixture.replace("selector: count", "selector: missing"), "selector"),
        ("missing exposure", fixture.replace("omitted: [count]", "omitted: []"), "count"),
        ("null omission list", fixture.replace("omitted: [count]", "omitted: null"), "list"),
        (
          "overlapping omission",
          fixture.replace("omitted: [count]", "omitted: [count, flag]"),
          "flag",
        ),
        ("duplicate name", fixture.replace("name: enabled", "name: root"), "name"),
        ("reserved TS name", fixture.replace("ts_name: packageName", "ts_name: schema"), "schema"),
        ("wrong override type", fixture.replace("value: false", "value: 2"), "default"),
        (
          "unknown adapter",
          fixture.replace("name: enabled", "name: enabled, adapter: arbitrary"),
          "adapter",
        ),
        (
          "missing interface",
          fixture.replace("            python: {preset: core_python}\n", ""),
          "python",
        ),
        (
          "preset conflict",
          fixture.replace("native: {preset: core}", "native: {preset: core, parameters: []}"),
          "preset",
        ),
        ("metadata shape", fixture.replace("value: {version: 1}", "value: wrong"), "catalog"),
        ("derived type failure", fixture.replace("typeof: string", "typeof: Label"), "Label"),
      )
      forEvery(cases.indices.toVector) { index =>
        val (name, yaml, expected) = cases(index)
        withClue(s"$name: ") {
          val errors = OptionSchemaReader.read(yaml, "invalid.yaml", Set("example")) match {
            case Left(value) => value
            case Right(_) => fail("invalid schema accepted")
          }
          errors should not be empty
          errors.foreach(_.location should startWith("invalid.yaml"))
          errors.map(_.message).mkString("\n").toLowerCase should include(expected.toLowerCase)
        }
      }
      val mismatch = OptionSchemaReader.read(fixture, "registry.yaml", Set("missing"))
      mismatch.left.toOption.get.map(_.message).mkString("\n") should include("missing")
      mismatch.left.toOption.get.map(_.message).mkString("\n") should include("example")
    }

    "reject names that collide in their emitted language and wrapper scope" in {
      val yaml = Files.readString(Path.of(sys.env("OPTION_SCHEMA")))
      val secondFormat = GenerationFixture.inClass(yaml, "LinkMlOptions", "FrictionlessOptions") {
        block =>
          val jsLine = "                - {selector: skipClassDerivation, name: skipDerivation}\n"
          val cliOmitted = block.lastIndexOf("              omitted: []\n")
          val exposed = block.take(cliOmitted) + "              omitted: [secondFormat]\n" +
            block.drop(cliOmitted + "              omitted: []\n".length)
          GenerationFixture.replaceOnce(
            GenerationFixture.replaceOnce(
              exposed,
              jsLine,
              jsLine + "                - {selector: secondFormat, name: secondFormat}\n",
            ),
            "      outputFormat:\n",
            "      secondFormat:\n        range: JsonOutputFormat\n        required: true\n" +
              "        rank: 4\n        ifabsent: JsonOutputFormat(yaml)\n      outputFormat:\n",
          )
      }
      val cases = Vector(
        ("Python keyword", fixture.replace("flag", "class"), "name"),
        ("Python native keyword", fixture.replace("flag", "'True'"), "name"),
        ("Python projected collision", yaml.replace("skipClassDerivation", "treeRoot"), "name"),
        ("Python receiver", fixture.replace("flag", "self"), "name"),
        ("Python transport", fixture.replace("flag", "function"), "name"),
        ("Python pruning helper", yaml.replace("skipClassDerivation", "_pruning"), "name"),
        ("TS effective keyword", fixture.replace("name: enabled", "name: class"), "name"),
        ("TS explicit keyword", fixture.replace("ts_name: packageName", "ts_name: class"), "name"),
        ("Scala inherited member", fixture.replace("flag", "hashCode"), "name"),
        ("Scala generated member", fixture.replace("flag", "productArity"), "name"),
        ("CLI inherited member", fixture.replace("name: count", "name: toString"), "name"),
        (
          "JS generator object",
          yaml.replace("name: treeRootOverride", "name: JsonSchemaGenerator"),
          "name",
        ),
        ("JS pruning object", yaml.replace("name: pruningMode", "name: PruningMode"), "name"),
        ("JS prelude kinds", secondFormat, "prelude"),
      )
      forEvery(cases) { case (name, broken, expected) =>
        withClue(s"$name: ")(rejects(broken, expected))
      }
      val valid = catalog(OptionSchemaReader.read(fixture.replace("flag", "using")))
      valid.generators.head.fields.map(_.name) should contain allOf ("package", "using")
      catalog(
        OptionSchemaReader.read(fixture.replace("name: enabled", "name: class, ts_name: enabled")),
      )
        .generators.head.profiles(Interface.Js).parameters(1).tsName shouldBe Some("enabled")
    }

    "reject unsupported slot and inherited scalar semantics" in {
      val constraints = Vector(
        "minimum_value: 5",
        "maximum_value: -1",
        "pattern: '[0-9]+'",
        "equals_number: 1",
        "equals_string: zero",
        "equals_string_in: [zero]",
        "equals_expression: '{flag}'",
        "structured_pattern: {syntax: '[0-9]+'}",
        "value_presence: ABSENT",
        "implicit_prefix: ex",
        "unit: {symbol: m}",
        "inlined: true",
        "identifier: true",
        "key: true",
      )
      forEvery(constraints) { constraint =>
        withClue(s"$constraint: ") {
          rejects(fixture.replace("rank: 3", s"rank: 3\n        $constraint"), "unsupported")
        }
      }
      forEvery(Vector("pattern: '[a-z]+'", "equals_string: fixed", "minimum_value: 5")) {
        constraint =>
          val yaml = fixture.replace(
            "    typeof: string",
            s"    typeof: ParentLabel\n  ParentLabel:\n    typeof: string\n    $constraint",
          )
          withClue(s"inherited $constraint: ")(rejects(yaml, "range"))
      }
      rejects(
        fixture.replace(
          "    description: Test options.",
          "    description: Test options.\n    slot_conditions: {flag: {equals_number: 1}}",
        ),
        "unsupported",
      )
      catalog(
        OptionSchemaReader.read(
          fixture.replace(
            "    typeof: string",
            "    typeof: ParentLabel\n  ParentLabel:\n    typeof: string\n    description: A label.\n    comments: [Metadata does not restrict values.]",
          ),
        ),
      ).generators.head.fields.find(_.name == "package").get.valueType shouldBe ValueType.Text
    }

    "reject changes to the supporting pruning contract" in {
      val yaml = Files.readString(Path.of(sys.env("OPTION_SCHEMA")))
      val cases = Vector(
        "inheritance" -> yaml.replace(
          "  PruningChoice:\n",
          "  PruningBase:\n    attributes:\n      extra: {range: string, required: true}\n  PruningChoice:\n    is_a: PruningBase\n",
        ),
        "member default" -> yaml.replace(
          "        range: PruningKind",
          "        range: PruningKind\n        ifabsent: PruningKind(skip)",
        ),
        "member constraint" -> yaml.replace(
          "        range: PruningKind",
          "        range: PruningKind\n        equals_string: skip",
        ),
        "changed rule" -> yaml.replace(
          "equals_string_in: [skip, schema]",
          "equals_string_in: [skip]",
        ),
        "changed presence" -> yaml.replace("value_presence: ABSENT", "value_presence: PRESENT"),
        "disabled rule" -> yaml.replace(
          "      - preconditions:",
          "      - deactivated: true\n        preconditions:",
        ),
        "extra rule constraint" -> yaml.replace(
          "equals_string_in: [skip, schema]",
          "equals_string_in: [skip, schema]\n              equals_string: skip",
        ),
        "binding enum inheritance" -> yaml.replace(
          "  JsonOutputFormat:\n",
          "  ExtraFormat:\n    permissible_values: {xml: {}}\n  JsonOutputFormat:\n    inherits: [ExtraFormat]\n",
        ),
      )
      forEvery(cases) { case (name, broken) =>
        withClue(s"$name: ")(rejects(broken, "unsupported"))
      }
      catalog(
        OptionSchemaReader.read(
          yaml.replace(
            "      - preconditions:",
            "      - description: Reject roots outside tree mode.\n        preconditions:",
          ),
        ),
      ).bindings.find(_.valueType == ValueType.Pruning).get.name shouldBe "PruningChoice"
    }

    "load the complete catalog with existing defaults and boundary differences" in {
      val source = sys.env("OPTION_SCHEMA")
      val result = catalog(OptionSchemaReader.load(source, ids))
      val generators = result.generators.map(g => g.id -> g).toMap
      generators.keySet shouldBe ids
      generators.view.mapValues(_.fields.map(_.default)).toMap shouldBe Map(
        "json_schema" -> Vector(
          Value.Bool(false),
          Value.Absent,
          Value.Absent,
          Value.Int32(2),
          Value.Text("en"),
          Value.Bool(false),
        ),
        "shacl" -> Vector(Value.Bool(false), Value.Bool(false), Value.Enum("ttl")),
        "rdfs" -> Vector(Value.Bool(false), Value.Enum("ttl")),
        "linkml" -> Vector(
          Value.Pruning(PruningKind.Skip, None),
          Value.Bool(false),
          Value.Enum("yaml"),
        ),
        "frictionless" -> Vector(
          Value.Pruning(PruningKind.Skip, None),
          Value.Bool(false),
          Value.Text("en"),
        ),
        "graphql" -> Vector(Value.Pruning(PruningKind.Schema, None), Value.Text("en")),
        "er_diagram" -> Vector(Value.Pruning(PruningKind.Schema, None), Value.Bool(true)),
        "ossie" -> Vector(
          Value.Pruning(PruningKind.Skip, None),
          Value.Enum("yaml"),
          Value.Text("en"),
        ),
        "scala" -> Vector(
          Value.Text("eu.neverblink.linkml.metamodel"),
          Value.Bool(true),
          Value.Text("en"),
        ),
        "translation" -> Vector(Value.Text("base"), Value.Int32(2)),
      )
      for id <- Vector("linkml", "frictionless", "graphql", "er_diagram", "ossie") do {
        val cliPruning =
          generators(id).profiles(Interface.Cli).parameters.find(_.name == "pruning").get
        cliPruning.adapter shouldBe Some(Adapter.PruningOptions)
        cliPruning.default shouldBe Some(Value.Pruning(PruningKind.Skip, None))
        val mode =
          generators(id).profiles(Interface.Js).parameters.find(_.name == "pruningMode").get
        val expected = if Set("linkml", "graphql", "er_diagram")(id) then "tree_root" else "skip"
        mode.default shouldBe Some(Value.Enum(expected))
      }
      generators("linkml").profiles(Interface.Python).parameters.map(_.name) shouldBe
        Vector("pruning_mode", "tree_root", "skip_class_derivation", "output_format")
      generators("linkml").profiles(Interface.Python).parameters.find(
        _.name == "tree_root",
      ).get.description shouldBe
        "Tree root class name to use instead of the schema-defined tree_root."
      generators("linkml").profiles(Interface.Cli).parameters.map(_.name) shouldBe
        Vector("skipDerivation", "pruning", "format")
      for id <- Vector("scala", "translation") do
        generators(id).profiles(Interface.Js).parameters.head.default shouldBe None
      for id <- Vector("shacl", "rdfs") do
        generators(id).scalaParent shouldBe Some(ScalaParent.RdfOptions)

      val yaml = Files.readString(Path.of(source))
      val invalid = Vector(
        ("unknown binding", yaml.replace("json_format", "unsupported_binding"), "binding"),
        (
          "unknown enum value",
          yaml.replace("JsonOutputFormat(yaml)", "JsonOutputFormat(xml)"),
          "xml",
        ),
        (
          "invalid pruning payload",
          yaml.replace("value: {mode: schema}", "value: {mode: schema, treeRoot: Person}"),
          "treeRoot",
        ),
        (
          "unknown pruning component",
          yaml.replace("selector: pruningMode.treeRoot", "selector: pruningMode.invalid"),
          "selector",
        ),
        (
          "unknown value annotation",
          yaml.replace(
            "description: YAML document.",
            "description: YAML document.\n        annotations:\n          optgen:typo: ignored",
          ),
          "optgen:typo",
        ),
        (
          "missing pruning kind",
          yaml.replace("      optgen:binding: pruning_kind\n", "").replace(
            "        range: PruningKind\n",
            "",
          ),
          "pruning",
        ),
      )
      forEvery(invalid.indices.toVector) { index =>
        val (name, broken, expected) = invalid(index)
        withClue(s"$name: ") {
          val errors = OptionSchemaReader.read(broken, "catalog.yaml", ids) match {
            case Left(value) => value
            case Right(_) => fail("invalid schema accepted")
          }
          errors.map(_.message).mkString("\n").toLowerCase should include(expected.toLowerCase)
          errors.foreach(_.location should startWith("catalog.yaml"))
        }
      }
    }
  }
}
