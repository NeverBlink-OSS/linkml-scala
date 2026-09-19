package eu.neverblink.linkml.optiongen

object Entrypoints {
  final case class Entrypoint(
      python: String,
      scalaMethod: String,
      generator: String,
      returns: String = "str",
      jsPrelude: Vector[ValueType] = Vector.empty,
  ) {
    def symbol: String = s"linkml_$python"

    def source: String = {
      val pkg = generator.stripSuffix("Generator").toLowerCase(java.util.Locale.ROOT)
      s"generator/src/eu/neverblink/linkml/generator/$pkg/$generator.scala"
    }

    def structured: Boolean = returns != "str"

    def jsMethod: String = python match {
      case "scala" | "frictionless" => python
      case _ => scalaMethod
    }

    def jsInvocation: String = python match {
      case "scala" => "generate"
      case "frictionless" => "generateFiles"
      case _ => "serialize"
    }
  }

  val all: Vector[Entrypoint] = Vector(
    Entrypoint("json_schema", "jsonSchema", "JsonSchemaGenerator"),
    Entrypoint("shacl", "shacl", "ShaclGenerator"),
    Entrypoint("rdfs", "rdfs", "RdfsGenerator"),
    Entrypoint(
      "linkml",
      "linkml",
      "LinkMlGenerator",
      jsPrelude = Vector(ValueType.Pruning, ValueType.JsonFormat),
    ),
    Entrypoint("frictionless", "frictionlessFiles", "FrictionlessGenerator", "dict[str, str]"),
    Entrypoint("graphql", "graphQl", "GraphQlGenerator"),
    Entrypoint("er_diagram", "erDiagram", "ErDiagramGenerator"),
    Entrypoint("ossie", "ossie", "OssieGenerator", jsPrelude = Vector(ValueType.JsonFormat)),
    Entrypoint("scala", "scalaFiles", "ScalaGenerator", "dict[str, str]"),
    Entrypoint("translation", "translation", "TranslationGenerator"),
  )
}
