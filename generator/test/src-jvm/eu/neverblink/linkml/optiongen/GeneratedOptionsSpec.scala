package eu.neverblink.linkml.optiongen

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GeneratedOptionsSpec extends AnyWordSpec, Matchers {
  private def run(sources: (String, String)*): Unit = {
    val root = os.temp.dir(prefix = "optiongen-scala-")
    try {
      val files = sources.map { case (name, content) =>
        val file = root / name
        os.write(file, content)
        file
      }
      val classes = GeneratedScala.compile(root, files)
      val result = GeneratedScala.run(classes, "generated.Probe", Seq.empty, root)
      withClue(s"generated Scala execution:\n${result.out.text()}\n${result.err.text()}") {
        result.exitCode shouldBe 0
      }
    } finally os.remove.all(root)
  }

  "Generated options" should {
    "compile contextual names and bound types without host imports" in {
      val fields = Vector(
        ("using", ValueType.Text, Value.Text("context")),
        ("package", ValueType.Text, Value.Text("example")),
        ("format", ValueType.RdfFormat, Value.Enum("nt")),
        ("outputFormat", ValueType.JsonFormat, Value.Enum("json")),
        ("pruningMode", ValueType.Pruning, Value.Pruning(PruningKind.Schema, None)),
      ).zipWithIndex.map { case ((name, valueType, default), index) =>
        FieldDef(name, index + 1, valueType, default, "", "fixture.yaml")
      }
      val generator = GeneratorDef(
        "shacl",
        "ProbeOptions",
        "Fixture options.",
        fields,
        Map.empty,
        Some(ScalaParent.RdfOptions),
      )
      val declaration = CoreGen.render(Entrypoints.all.find(_.python == "shacl").get, generator)
      run(
        "Options.scala" -> s"package generated\nobject Host {\n$declaration}\n",
        "Probe.scala" -> """package generated
          |object Probe {
          |  def main(args: Array[String]): Unit = {
          |    val options = Host.Options()
          |    val rdf: eu.neverblink.linkml.generator.rdf.RdfOptions = options
          |    assert(options.`using` == "context" && options.`package` == "example")
          |    assert(rdf.format == eu.neverblink.linkml.generator.rdf.RdfFormat.nt)
          |    assert(options.outputFormat == eu.neverblink.linkml.generator.util.JsonOutputFormat.json)
          |    assert(options.pruningMode == eu.neverblink.linkml.generator.util.PruningMode.schemaRoot)
          |  }
          |}
          |""".stripMargin,
      )
    }

    "execute renamed conversion parameters and omitted options using core defaults" in {
      val catalog = OptionSchemaReader.load(sys.env("OPTION_SCHEMA")).toOption.get
      def changed(omitFormat: Boolean): Catalog = catalog.copy(generators = catalog.generators.map {
        generator =>
          if !Set("linkml", "ossie", "shacl")(generator.id) then generator
          else {
            val profile = generator.profiles(Interface.Js)
            val omitted = Set("pruningMode") ++ Option.when(omitFormat)("outputFormat")
            val parameters =
              profile.parameters.filterNot(p => omitted(p.selector.field)).map { parameter =>
                val name = parameter.valueType match {
                  case ValueType.JsonFormat => "outputFormat"
                  case ValueType.RdfFormat => "rdfFormat"
                  case _ => parameter.name
                }
                parameter.copy(name = name)
              }
            generator.copy(profiles =
              generator.profiles.updated(
                Interface.Js,
                profile.copy(parameters = parameters, omitted = profile.omitted ++ omitted),
              ),
            )
          }
      })
      val renamed = JsGen.regions(changed(omitFormat = false))
      val omitted = JsGen.regions(changed(omitFormat = true))
      val helpers = """private def outputFormat(format: String): JsonOutputFormat =
        |  JsonOutputFormat.parse(format).getOrElse(
        |    throw RuntimeException(JsonOutputFormat.unknownFormat(format)))
        |private def rdfFormat(format: String): RdfFormat = format.toLowerCase match {
        |  case "nt" | "ntriples" => RdfFormat.nt
        |  case "ttl" | "turtle" => RdfFormat.ttl
        |  case other => throw RuntimeException(s"Unknown RDF format: $other. Supported formats: nt, ttl.")
        |}
        |""".stripMargin
      run("Probe.scala" -> raw"""package generated
        |import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
        |import eu.neverblink.linkml.generator.ossie.OssieGenerator
        |import eu.neverblink.linkml.generator.shacl.ShaclGenerator
        |import eu.neverblink.linkml.generator.util.JsonOutputFormat
        |import eu.neverblink.linkml.generator.rdf.RdfFormat
        |import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
        |final case class SchemaViewJs(underlying: SchemaView)
        |object Renamed {
        |$helpers
        |${renamed("js.linkml")}
        |${renamed("js.ossie")}
        |${renamed("js.shacl")}
        |}
        |object Omitted {
        |${omitted("js.linkml")}
        |${omitted("js.ossie")}
        |}
        |object Probe {
        |  def main(args: Array[String]): Unit = {
        |    val view = SchemaViewJs(SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(
        |      "id: https://example.org/fixture\nname: fixture\ndefault_prefix: ex\nprefixes:\n  ex: https://example.org/fixture/\nclasses:\n  Root:\n    tree_root: true\n  Unused:\n")))
        |    for output <- Seq(Renamed.linkml(view, outputFormat = "json"),
        |                       Renamed.ossie(view, outputFormat = "json")) do {
        |      assert(io.circe.parser.parse(output).isRight, output)
        |      assert(output.contains("Unused"), output)
        |    }
        |    for output <- Seq(Omitted.linkml(view), Omitted.ossie(view)) do {
        |      assert(io.circe.parser.parse(output).isLeft, output)
        |      assert(output.contains("Unused"), output)
        |    }
        |    val triples = Renamed.shacl(view, rdfFormat = "nt")
        |    assert(triples.contains("<http://www.w3.org/ns/shacl#NodeShape>"), triples)
        |    assert(!triples.contains("PREFIX "), triples)
        |    assert(Renamed.shacl(view).contains("PREFIX "))
        |  }
        |}
        |""".stripMargin)
    }
  }
}
