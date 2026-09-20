package eu.neverblink.linkml.optiongen

object CoreGen {
  private val util = "_root_.eu.neverblink.linkml.generator.util"
  private val rdf = "_root_.eu.neverblink.linkml.generator.rdf"

  def render(entry: Entrypoints.Entrypoint, generator: GeneratorDef): String = {
    require(entry.python == generator.id, s"Mismatched generator '${generator.id}'")
    val fields = generator.fields.sortBy(_.rank)
    val description = Literals.commentLines(generator.description)
    val docs = new StringBuilder
    docs.append("/** ").append(description.head).append('\n')
    description.tail.foreach(line => docs.append("  * ").append(line).append('\n'))
    fields.filter(_.description.nonEmpty).foreach { field =>
      docs.append("  * @param ").append(field.name).append('\n')
      Literals.commentLines(field.description).foreach { line =>
        docs.append("  *   ").append(line).append('\n')
      }
    }
    docs.append("  */\n")
    val parameters = fields.map { field =>
      s"    ${Literals.scalaIdentifier(field.name)}: ${scalaType(field)} = ${default(field)},\n"
    }.mkString
    val parent = generator.scalaParent match {
      case Some(ScalaParent.RdfOptions) => s" extends $rdf.RdfOptions"
      case None => ""
    }
    s"${docs}final case class Options(\n$parameters)$parent\n"
  }

  private def scalaType(field: FieldDef): String = field.valueType match {
    case ValueType.Text => "String"
    case ValueType.Bool => "Boolean"
    case ValueType.Int32 => "Int"
    case ValueType.OptionalText => "Option[String]"
    case ValueType.JsonFormat => s"$util.JsonOutputFormat"
    case ValueType.RdfFormat => s"$rdf.RdfFormat"
    case ValueType.Pruning => s"$util.PruningMode"
    case ValueType.PruningKind =>
      throw IllegalArgumentException(s"${field.location}: Pruning kind is not a core Scala type")
  }

  private def default(field: FieldDef): String =
    (field.valueType, field.default) match {
      case (ValueType.Text, Value.Text(value)) => Literals.quote(value)
      case (ValueType.Bool, Value.Bool(value)) => value.toString
      case (ValueType.Int32, Value.Int32(value)) => value.toString
      case (ValueType.OptionalText, Value.Text(value)) => s"Some(${Literals.quote(value)})"
      case (ValueType.OptionalText, Value.Absent) => "None"
      case (ValueType.JsonFormat, Value.Enum(value @ ("json" | "yaml"))) =>
        s"$util.JsonOutputFormat.$value"
      case (ValueType.RdfFormat, Value.Enum(value @ ("ttl" | "nt"))) => s"$rdf.RdfFormat.$value"
      case (ValueType.Pruning, Value.Pruning(PruningKind.Skip, None)) => s"$util.PruningMode.skip"
      case (ValueType.Pruning, Value.Pruning(PruningKind.Schema, None)) =>
        s"$util.PruningMode.schemaRoot"
      case (ValueType.Pruning, Value.Pruning(PruningKind.TreeRoot, root)) =>
        val argument = root.fold("None")(value => s"Some(${Literals.quote(value)})")
        s"$util.PruningMode.treeRoot($argument)"
      case _ =>
        throw IllegalArgumentException(
          s"${field.location}: Unsupported core default ${field.default}",
        )
    }
}
