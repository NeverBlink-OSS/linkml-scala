package eu.neverblink.linkml.optiongen

object CliGen {
  val host: String = "cli/src/eu/neverblink/linkml/cli/GenerateImpl.scala"
  val pruningHost: String = "cli/src/eu/neverblink/linkml/cli/PruningOptions.scala"

  def regions(catalog: Catalog): Map[String, String] = {
    val _ = sharedPruningDefault(catalog)
    val generators = catalog.generators.map(generator => generator.id -> generator).toMap
    Entrypoints.all.flatMap { entry =>
      val generator = generators(entry.python)
      val profile = generator.profiles(Interface.Cli)
      Vector(
        s"cli.${entry.python}.fields" -> profile.parameters.map(field).mkString,
        s"cli.${entry.python}.options" -> options(entry, generator, profile),
      )
    }.toMap
  }

  def pruningRegions(catalog: Catalog): Map[String, String] = {
    val (mode, root) = sharedPruningDefault(catalog)
    val pruning = catalog.bindings.find(_.valueType == ValueType.Pruning).get
    val kinds = catalog.bindings.find(_.valueType == ValueType.PruningKind).get
    val choices =
      kinds.members.map(member => s"${pruningName(member.name)} - ${member.description}")
    val modeHelp = (
      Vector(pruning.attributeDescriptions("mode")) ++ choices :+ s"Default: ${pruningName(mode)}."
    ).mkString("\n")
    val rootHelp = pruning.attributeDescriptions("treeRoot")
    val modeDefault = mode match {
      case PruningKind.Skip => "PruningMode.skip"
      case PruningKind.Schema => "PruningMode.schemaRoot"
      case PruningKind.TreeRoot => "PruningMode.treeRoot(None)"
    }
    val rootDefault = root.fold("None")(value => s"Some(${Literals.quote(value)})")
    val fields =
      s"""    @HelpMessage(${Literals.quote(modeHelp)})
         |    pruningMode: PruningMode = $modeDefault,
         |    @HelpMessage(${Literals.quote(rootHelp)})
         |    treeRoot: Option[String] = $rootDefault,
         |""".stripMargin
    val names = kinds.members.map(member => Literals.quote(pruningName(member.name))).mkString(", ")
    Map(
      "cli.pruning.fields" -> fields,
      "cli.pruning.names" -> s"  private val names = Seq($names)\n",
    )
  }

  private def field(parameter: Parameter): String = {
    val annotation = parameter.adapter match {
      case Some(Adapter.PruningOptions) => "@Recurse"
      case Some(Adapter.TranslationTargets) =>
        s"@HelpMessage(${Literals.quote(parameter.description + " One of: ")} + TranslationGenerator.availableValues)"
      case None => s"@HelpMessage(${Literals.quote(parameter.description)})"
    }
    val default = parameter.default.fold("")(value => s" = ${defaultValue(parameter, value)}")
    s"    $annotation\n    ${Literals.scalaIdentifier(parameter.name)}: ${fieldType(parameter)}$default,\n"
  }

  private def fieldType(parameter: Parameter): String = parameter.valueType match {
    case ValueType.Text | ValueType.JsonFormat | ValueType.RdfFormat => "String"
    case ValueType.Bool => "Boolean"
    case ValueType.Int32 => "Int"
    case ValueType.OptionalText => "Option[String]"
    case ValueType.Pruning if parameter.adapter.contains(Adapter.PruningOptions) => "PruningOptions"
    case other => throw IllegalArgumentException(s"Unsupported CLI parameter type $other")
  }

  private def defaultValue(parameter: Parameter, value: Value): String =
    (parameter.valueType, value) match {
      case (ValueType.Text, Value.Text(text)) => Literals.quote(text)
      case (ValueType.Bool, Value.Bool(boolean)) => boolean.toString
      case (ValueType.Int32, Value.Int32(number)) => number.toString
      case (ValueType.OptionalText, Value.Absent) => "None"
      case (ValueType.OptionalText, Value.Text(text)) => s"Some(${Literals.quote(text)})"
      case (ValueType.JsonFormat | ValueType.RdfFormat, Value.Enum(name)) => Literals.quote(name)
      case (ValueType.Pruning, Value.Pruning(_, _))
          if parameter.adapter.contains(Adapter.PruningOptions) =>
        "PruningOptions()"
      case _ =>
        throw IllegalArgumentException(s"Unsupported CLI default for ${parameter.name}: $value")
    }

  private def options(
      entry: Entrypoints.Entrypoint,
      generator: GeneratorDef,
      profile: Profile,
  ): String = {
    require(
      profile.parameters.forall(_.selector.component.isEmpty),
      "CLI mapping requires whole-field selectors",
    )
    val parameters =
      profile.parameters.map(parameter => parameter.selector.field -> parameter).toMap
    val indent = if entry.python == "frictionless" then "    " else "      "
    val arguments = generator.fields.sortBy(_.rank).filter(field =>
      parameters.contains(field.name),
    ).map { field =>
      s"$indent  ${Literals.scalaIdentifier(field.name)} = ${argument(parameters(field.name))},\n"
    }.mkString
    val separator = if entry.python == "frictionless" then "" else ","
    s"$indent${entry.generator}.Options(\n$arguments$indent)$separator\n"
  }

  private def argument(parameter: Parameter): String = {
    val value = s"options.${Literals.scalaIdentifier(parameter.name)}"
    parameter.valueType match {
      case ValueType.Text | ValueType.Bool | ValueType.Int32 | ValueType.OptionalText => value
      case ValueType.JsonFormat =>
        s"JsonOutputFormat.parse($value).getOrElse(err(JsonOutputFormat.unknownFormat($value)))"
      case ValueType.RdfFormat =>
        s"RdfOutput.parse($value).getOrElse(err(RdfOutput.unknownFormat($value)))"
      case ValueType.Pruning if parameter.adapter.contains(Adapter.PruningOptions) =>
        s"$value.resolvedPruningMode"
      case other => throw IllegalArgumentException(s"Unsupported CLI argument type $other")
    }
  }

  private def sharedPruningDefault(catalog: Catalog): (PruningKind, Option[String]) = {
    val defaults = catalog.generators.flatMap(_.profiles(Interface.Cli).parameters)
      .filter(_.adapter.contains(Adapter.PruningOptions)).map { parameter =>
        parameter.default match {
          case Some(Value.Pruning(mode, root)) => mode -> root
          case _ =>
            throw IllegalArgumentException(s"Missing CLI pruning default for ${parameter.name}")
        }
      }.distinct
    require(defaults.size == 1, "CLI pruning defaults must agree across profiles")
    defaults.head
  }

  private def pruningName(mode: PruningKind): String = mode match {
    case PruningKind.Skip => "skip"
    case PruningKind.Schema => "schema"
    case PruningKind.TreeRoot => "treeRoot"
  }

  private def pruningName(name: String): String =
    if name == "tree_root" then "treeRoot" else name
}
