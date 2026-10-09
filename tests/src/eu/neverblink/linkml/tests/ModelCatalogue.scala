package eu.neverblink.linkml.tests

import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView, yamlAs}

import scala.jdk.CollectionConverters.*

/** Container for the test model catalogue
  *
  * There is an entry for every model in `/models/` of the resources, generated into
  * [[ModelCatalogueEntries]], like `ModelCatalogue.inlines.explicitInline` for
  * `/models/inlines/explicitInline/`.
  */
object ModelCatalogue extends ModelCatalogueEntries {

  /** Instance of a model in different serialization formats
    *
    * @param json
    *   The instance in JSON format if provided
    * @param turtle
    *   The instance in Turtle format if provided
    * @param csv
    *   The instance in CSV format if provided
    * @param context
    *   Additional RDF data containing instances referenced in [[turtle]]. Instance validation
    *   should be run with this added in.
    */
  case class InstanceInFormats private (
      name: String,
      json: Option[String],
      turtle: Option[String],
      csv: Option[String],
      context: Option[String],
      additionalFiles: Map[String, String],
  ):
    /** @return
      *   the requested format if available, None otherwise
      */
    def inFormat(format: Format): Option[String] = format match {
      case Format.turtle => turtle
      case Format.turtleWithContext =>
        for
          t <- turtle
          c <- context
        yield t + c
      case Format.json => json
      case Format.csv => csv
    }

    /** @return
      *   true if the format is available
      */
    def hasFormat(format: Format): Boolean = inFormat(format).isDefined

  /** Enum for formats of an instance
    */
  enum Format:
    case turtle, turtleWithContext, json, csv

  private object InstanceInFormats:
    def apply(path: String, name: String): InstanceInFormats = {
      val additionalFiles = Resources.map.keySet().asScala
        .filter(_.startsWith(path + "/"))

      val json = path + "/data.json"
      val turtle = path + "/data.ttl"
      val csv = path + "/data.csv"
      val context = path + "/context.ttl"

      val baseFiles = Seq(json, turtle, csv, context)

      new InstanceInFormats(
        name,
        if Resources.map.containsKey(json) then Some(Resources.read(json)) else None,
        if Resources.map.containsKey(turtle) then Some(Resources.read(turtle)) else None,
        if Resources.map.containsKey(csv) then Some(Resources.read(csv)) else None,
        if Resources.map.containsKey(context) then Some(Resources.read(context)) else None,
        additionalFiles
          .collect {
            case file if !baseFiles.contains(file) =>
              file.stripPrefix(path + "/") -> Resources.read(file)
          }
          .toMap,
      )
    }

  /** Model catalogue entry
    * @param path
    *   Path to the directory the model is located
    * @param model
    *   Parsed SchemaView representing the model
    * @param validInstances
    *   Instances of the `tree_root` class of the model in different formats
    * @param invalidInstances
    *   Invalid instances of the `tree_root` class of the model in different formats
    */
  case class Entry private (
      path: String,
      id: String,
      model: SchemaView,
      validInstances: Seq[InstanceInFormats],
      invalidInstances: Seq[InstanceInFormats],
  ):
    val name: String = model.root.name

  private[tests] object Entry:
    def apply(path: String): Entry = {
      val instancePaths = Resources.map.keySet().asScala.toSeq
        .filter(x => x.endsWith("/data.json") || x.endsWith("/data.ttl") || x.endsWith("/data.csv"))
        .map(_.stripSuffix("/data.json").stripSuffix("/data.ttl").stripSuffix("/data.csv"))
        .distinct

      val validInstancePaths = instancePaths.filter(_.startsWith(path + "valid/"))

      val invalidInstancePaths = instancePaths.filter(_.startsWith(path + "invalid/"))

      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri(path + "model.yaml", importer = CatalogueImporter),
      )

      new Entry(
        path,
        sv.root.id.original,
        sv,
        validInstancePaths.map(instance =>
          InstanceInFormats(instance, instance.stripPrefix(path + "valid/")),
        ),
        invalidInstancePaths.map(instance =>
          InstanceInFormats(instance, instance.stripPrefix(path + "invalid/")),
        ),
      )
    }

  /** All model catalogue entries that do not have the `opt_in` flag set. */
  lazy val all: Seq[Entry] = allOptIn
    .filter(
      !_.model.root.extensions.get("opt_in").flatMap(
        _.extensionValue.yamlAs[Boolean].toOption,
      ).contains(true),
    )
}
