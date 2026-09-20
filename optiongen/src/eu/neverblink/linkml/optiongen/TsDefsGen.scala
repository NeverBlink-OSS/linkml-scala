package eu.neverblink.linkml.optiongen

object TsDefsGen {
  private val placeholder = "{{GENERATOR_METHODS}}"

  // Header for generated .d.ts output.
  private val outputHeader =
    "// AUTO-GENERATED from model/generator-options.yaml and generator/npm/api.d.ts.template.\n" +
      "// Do not edit by hand - regenerate with ./mill uiTypes (or generator.js.npmPackage).\n\n"

  def apply(catalog: Catalog, template: String): String = {
    require(
      template.sliding(placeholder.length).count(_ == placeholder) == 1,
      "Expected exactly one generator-method placeholder in the TS template",
    )
    val generators = catalog.generators.map(generator => generator.id -> generator).toMap
    val members = Entrypoints.all.map { entry =>
      val generator = generators(entry.python)
      val profile = generator.profiles(Interface.Js)
      def name(parameter: Parameter): String = parameter.tsName.getOrElse(parameter.name)
      def required(parameter: Parameter): Boolean =
        parameter.default.isEmpty && parameter.valueType != ValueType.OptionalText
      val lastRequired = profile.parameters.lastIndexWhere(required)
      val parameters = Vector("schema: SchemaView") ++ profile.parameters.zipWithIndex.map {
        case (parameter, index) =>
          val tpe = tsType(parameter.valueType)
          if required(parameter) then s"${name(parameter)}: $tpe"
          else if index < lastRequired then s"${name(parameter)}: $tpe | undefined"
          else s"${name(parameter)}?: $tpe"
      }
      val parameterDocs = Vector("schema" -> JsGen.schemaDescription) ++ profile.parameters.map {
        p =>
          name(p) -> JsGen.parameterDescription(catalog, p)
      }
      val docs = Vector("  /**") ++
        Literals.commentLines(generator.description).map(line => s"   * $line") ++
        parameterDocs.flatMap { case (name, description) =>
          val lines = Literals.commentLines(description)
          Vector(s"   * @param $name ${lines.head}") ++ lines.tail.map(line => s"   * $line")
        } ++ Vector(s"   * @returns ${JsGen.returnDescription(entry)}", "   */")
      val result = if entry.structured then "Record<string, string>" else "string"
      s"${docs.mkString("\n")}\n  ${entry.jsMethod}(${parameters.mkString(", ")}): $result;"
    }.mkString("\n\n")
    outputHeader + template.replace(placeholder, members)
  }

  private def tsType(valueType: ValueType): String = valueType match {
    case ValueType.Bool => "boolean"
    case ValueType.Int32 => "number"
    case ValueType.Text | ValueType.OptionalText | ValueType.JsonFormat | ValueType.RdfFormat |
        ValueType.PruningKind =>
      "string"
    case ValueType.Pruning => throw IllegalArgumentException("JS pruning must use split selectors")
  }
}
