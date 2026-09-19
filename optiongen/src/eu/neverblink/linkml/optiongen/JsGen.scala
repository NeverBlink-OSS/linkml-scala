package eu.neverblink.linkml.optiongen

object JsGen {
  val host: String = "generator/src-js/eu/neverblink/linkml/js/LinkMlJsApi.scala"

  private[optiongen] val schemaDescription: String =
    "A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]]."

  def regions(catalog: Catalog): Map[String, String] = {
    val generators = catalog.generators.map(generator => generator.id -> generator).toMap
    Entrypoints.all.map { entry =>
      s"js.${entry.python}" -> render(catalog, entry, generators(entry.python))
    }.toMap
  }

  private def render(
      catalog: Catalog,
      entry: Entrypoints.Entrypoint,
      generator: GeneratorDef,
  ): String = {
    val profile = generator.profiles(Interface.Js)
    val docs = new StringBuilder
    val summary = Literals.commentLines(generator.description)
    docs.append("/** ").append(summary.head).append('\n')
    summary.tail.foreach(line => docs.append("  * ").append(line).append('\n'))
    val parameterDocs = Vector("schema" -> schemaDescription) ++ profile.parameters.map {
      parameter =>
        Literals.scalaIdentifier(parameter.name) -> parameterDescription(catalog, parameter)
    }
    parameterDocs.foreach { case (name, description) =>
      docs.append("  * @param ").append(name).append('\n')
      Literals.commentLines(description).foreach { line =>
        docs.append("  *   ").append(line).append('\n')
      }
    }
    docs.append("  * @return\n")
    Literals.commentLines(returnDescription(entry)).foreach { line =>
      docs.append("  *   ").append(line).append('\n')
    }
    docs.append("  */\n")

    val parameters = profile.parameters.map { parameter =>
      val default = parameter.default.fold("")(value => s" = ${jsDefault(parameter, value)}")
      s"    ${Literals.scalaIdentifier(parameter.name)}: ${scalaType(parameter.valueType)}$default,\n"
    }.mkString
    val byField = profile.parameters.groupBy(_.selector.field)
    val fields = generator.fields.sortBy(_.rank).filter(field => byField.contains(field.name))
    val expressions = fields.map { field =>
      field.name -> argument(field, byField(field.name))
    }.toMap
    val names = profile.parameters.map(_.name).toSet + "schema"
    def local(base: String): String =
      Iterator.iterate(base)(_ + "_").find(name => !names(name)).get
    val bindings = entry.jsPrelude.flatMap { kind =>
      val matching = fields.filter(_.valueType == kind)
      require(matching.size <= 1, s"Expected at most one $kind field for JS ${entry.python}")
      val base = if kind == ValueType.Pruning then "mode" else "format"
      matching.map(field => field.name -> local(base))
    }
    val boundNames = bindings.toMap
    val arguments = fields.map { field =>
      val value = boundNames.getOrElse(field.name, expressions(field.name))
      s"      ${Literals.scalaIdentifier(field.name)} = $value,\n"
    }.mkString
    val conversion = if entry.structured then ".toMap.toJSDictionary" else ""
    val invocation =
      s"""  ${entry.generator}(using schema.underlying).${entry.jsInvocation}(
         |    ${entry.generator}.Options(
         |$arguments    ),
         |  )$conversion""".stripMargin
    val body = if bindings.isEmpty then s"\n$invocation\n"
    else {
      val prelude = bindings.map { case (field, name) =>
        s"  val $name = ${expressions(field)}\n"
      }.mkString
      s" {\n$prelude$invocation\n}\n"
    }
    val result = if entry.structured then "js.Dictionary[String]" else "String"
    s"${docs}def ${entry.jsMethod}(\n    schema: SchemaViewJs,\n$parameters): $result =$body"
  }

  private def argument(field: FieldDef, parameters: Vector[Parameter]): String = {
    def name(parameter: Parameter): String = Literals.scalaIdentifier(parameter.name)
    if field.valueType == ValueType.Pruning then {
      def component(kind: PruningComponent): Parameter =
        parameters.find(_.selector.component.contains(kind))
          .getOrElse(throw IllegalArgumentException(s"Missing $kind for ${field.name}"))
      val mode = name(component(PruningComponent.Mode))
      val root = name(component(PruningComponent.TreeRoot))
      s"PruningMode($mode, $root.toOption)"
    } else {
      require(parameters.size == 1, s"Expected one JS parameter for ${field.name}")
      val value = name(parameters.head)
      field.valueType match {
        case ValueType.Text | ValueType.Bool | ValueType.Int32 => value
        case ValueType.OptionalText => s"$value.toOption"
        case ValueType.RdfFormat => s"this.rdfFormat($value)"
        case ValueType.JsonFormat => s"this.outputFormat($value)"
        case _ => throw IllegalArgumentException(s"Unsupported JS field type ${field.valueType}")
      }
    }
  }

  private def scalaType(valueType: ValueType): String = valueType match {
    case ValueType.Text | ValueType.JsonFormat | ValueType.RdfFormat | ValueType.PruningKind =>
      "String"
    case ValueType.Bool => "Boolean"
    case ValueType.Int32 => "Int"
    case ValueType.OptionalText => "js.UndefOr[String]"
    case ValueType.Pruning => throw IllegalArgumentException("JS pruning must use split selectors")
  }

  private def jsDefault(parameter: Parameter, value: Value): String = value match {
    case Value.Text(text) => Literals.quote(text)
    case Value.Bool(boolean) => boolean.toString
    case Value.Int32(number) => number.toString
    case Value.Absent => "js.undefined"
    case Value.Enum(name) => Literals.quote(enumName(parameter.valueType, name))
    case Value.Pruning(_, _) =>
      throw IllegalArgumentException("JS pruning defaults must use split selectors")
  }

  private def enumName(valueType: ValueType, name: String): String =
    if valueType == ValueType.PruningKind && name == "tree_root" then "treeRoot" else name

  private[optiongen] def parameterDescription(catalog: Catalog, parameter: Parameter): String = {
    val choices = catalog.bindings.find(_.valueType == parameter.valueType).toVector
      .flatMap(_.members).map { member =>
        s"`${enumName(parameter.valueType, member.name)}` - ${member.description}"
      }
    val values = if choices.isEmpty then "" else s" ${choices.mkString(" ")}"
    val pruning = if parameter.selector.component.contains(PruningComponent.TreeRoot) then
      " Ignored outside tree-root pruning mode."
    else ""
    val default = parameter.default.fold("") { value =>
      val displayed = if value == Value.Absent then "undefined" else jsDefault(parameter, value)
      s" Default: `$displayed`."
    }
    parameter.description + values + pruning + default
  }

  private[optiongen] def returnDescription(entry: Entrypoints.Entrypoint): String =
    entry.python match {
      case "json_schema" => "Serialized JSON Schema"
      case "shacl" => "SHACL shapes in the requested format"
      case "rdfs" => "RDFS in the requested format"
      case "linkml" => "The derived [[SchemaDefinition]] serialized in the specified format."
      case "frictionless" =>
        "JS dictionary (object) containing a mapping from filename to file content: " +
          "a `datapackage.json` plus one `schemas/<table>.json` per table."
      case "graphql" => "The serialized GraphQL schema."
      case "er_diagram" => "The ER diagram, serialized as Mermaid"
      case "ossie" => "The ontology, serialized in the specified format."
      case "scala" =>
        "JS dictionary (object) containing a mapping from filename to the generated Scala code."
      case "translation" =>
        "Translation dictionary for translating the linkml names to framework names."
      case other => throw IllegalArgumentException(s"Unknown generator: $other")
    }
}
