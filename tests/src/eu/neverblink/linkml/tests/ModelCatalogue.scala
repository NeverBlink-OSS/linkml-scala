package eu.neverblink.linkml.tests

import eu.neverblink.linkml.schemaview.{SchemaView, yamlAs}

import scala.jdk.CollectionConverters.*

/** Container for the test model catalogue */
object ModelCatalogue {

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
      val json = path + "/data.json"
      val turtle = path + "/data.ttl"
      val csv = path + "/data.csv"
      val context = path + "/context.ttl"
      new InstanceInFormats(
        name,
        if Resources.map.containsKey(json) then Some(Resources.read(json)) else None,
        if Resources.map.containsKey(turtle) then Some(Resources.read(turtle)) else None,
        if Resources.map.containsKey(csv) then Some(Resources.read(csv)) else None,
        if Resources.map.containsKey(context) then Some(Resources.read(context)) else None,
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

  private object Entry:
    def apply(path: String): Entry = {
      val instancePaths = Resources.map.keySet().asScala.toSeq
        .filter(x => x.endsWith("/data.json") || x.endsWith("/data.ttl"))
        .map(_.stripSuffix("/data.json").stripSuffix("/data.ttl"))
        .distinct

      val validInstancePaths = instancePaths.filter(_.startsWith(path + "valid/"))

      val invalidInstancePaths = instancePaths.filter(_.startsWith(path + "invalid/"))

      val sv = SchemaView.loadSchemaViewFromUri(path + "model.yaml", importer = CatalogueImporter)

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

  /** All model catalogue entries, including those with the opt_in flag set */
  lazy val allOptIn: Seq[Entry] = Resources.map.keySet().asScala.toSeq
    .filter(_.endsWith("model.yaml"))
    .map(_.stripSuffix("model.yaml"))
    .map(Entry(_))

  /** All model catalogue entries that do not have the `opt_in` flag set. */
  lazy val all: Seq[Entry] = allOptIn
    .filter(
      !_.model.root.extensions.get("opt_in").flatMap(
        _.extensionValue.yamlAs[Boolean].toOption,
      ).contains(true),
    )

  // TODO: generate this automatically maybe
  val `abstract`: Entry = Entry("/models/abstract/")
  val aliases: Entry = Entry("/models/aliases/")
  val anything: Entry = Entry("/models/anything/")
  val basic: Entry = Entry("/models/basic/")
  val basic2: Entry = Entry("/models/basic2/")
  val constraints: Entry = Entry("/models/constraints/")
  val constraintsOnTypes: Entry = Entry("/models/constraintsOnTypes/")
  val curie: Entry = Entry("/models/curie/")
  val emitPrefixes: Entry = Entry("/models/emitPrefixes/")
  val `enum`: Entry = Entry("/models/enum/")
  val externalType: Entry = Entry("/models/externalType/")
  val implicitPrefix: Entry = Entry("/models/implicitPrefix/")
  val inheritance: Entry = Entry("/models/inheritance/")
  val mixin: Entry = Entry("/models/mixin/")
  val multivaluedReference: Entry = Entry("/models/multivaluedReference/")
  val pruning: Entry = Entry("/models/pruning/")
  val pruningDefaultRange: Entry = Entry("/models/pruningDefaultRange/")
  val reference: Entry = Entry("/models/reference/")
  val referenceInteger: Entry = Entry("/models/referenceInteger/")
  val syntheticUris: Entry = Entry("/models/syntheticUris/")
  val treeRootless: Entry = Entry("/models/treeRootless/")
  val typed: Entry = Entry("/models/typed/")
  val unionRange: Entry = Entry("/models/unionRange/")
  val unionRangeReference: Entry = Entry("/models/unionRangeReference/")
  val uri: Entry = Entry("/models/uri/")
  val uriOrCurie: Entry = Entry("/models/uriOrCurie/")
  val uriImports: Entry = Entry("/models/uriImports/")

  object inlines {
    val explicitInline: Entry = Entry("/models/inlines/explicitInline/")
    val implicitInlineAsCompactDict: Entry = Entry(
      "/models/inlines/implicitInlineAsCompactDict/",
    )
    val implicitInlineAsList: Entry = Entry("/models/inlines/implicitInlineAsList/")
    val implicitInline: Entry = Entry("/models/inlines/implicitInline/")
    val implicitInlineAsSimpleDict: Entry = Entry("/models/inlines/implicitInlineAsSimpleDict/")

    val explicitInlineImplicitlyAsList: Entry = Entry(
      "/models/inlines/explicitInlineImplicitlyAsList/",
    )
    val explicitInlineImplicitlyAsCompactDict: Entry =
      Entry("/models/inlines/explicitInlineImplicitlyAsCompactDict/")
    val explicitInlineImplicitlyAsSimpleDict: Entry =
      Entry("/models/inlines/explicitInlineImplicitlyAsSimpleDict/")
    val explicitInlineList: Entry = Entry("/models/inlines/explicitInlineList/")

    val selfSimple2: Entry = Entry("/models/inlines/selfSimple2/")
    val selfSimple2Required: Entry = Entry("/models/inlines/selfSimple2Required/")
    val selfCompact1: Entry = Entry("/models/inlines/selfCompact1/")
    val selfCompact3: Entry = Entry("/models/inlines/selfCompact3/")
    val selfCompact3Required: Entry = Entry("/models/inlines/selfCompact3Required/")
  }

  object metadata {
    val title: Entry = Entry("/models/metadata/title/")
  }
}
