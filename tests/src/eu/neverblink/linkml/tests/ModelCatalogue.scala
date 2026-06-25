package eu.neverblink.linkml.tests

import eu.neverblink.linkml.schemaview.SchemaView

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
  )

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
    * @param model
    *   Parsed SchemaView representing the model
    * @param validInstances
    *   Instances of the `tree_root` class of the model in different formats
    * @param invalidInstances
    *   Invalid instances of the `tree_root` class of the model in different formats
    */
  case class Entry private (
      model: SchemaView,
      validInstances: Seq[InstanceInFormats],
      invalidInstances: Seq[InstanceInFormats],
  )

  private object Entry:
    def apply(path: String): Entry = {
      val instancePaths = Resources.map.keySet().asScala.toSeq
        .filter(_.endsWith("/data.json"))
        .map(_.stripSuffix("/data.json"))

      val validInstancePaths = instancePaths.filter(_.startsWith(path + "valid/"))

      val invalidInstancePaths = instancePaths.filter(_.startsWith(path + "invalid/"))

      new Entry(
        SchemaView.loadSchemaViewFromUri(path + "model.yaml", importer = CatalogueImporter),
        validInstancePaths.map(instance =>
          InstanceInFormats(instance, instance.stripPrefix(path + "valid/")),
        ),
        invalidInstancePaths.map(instance =>
          InstanceInFormats(instance, instance.stripPrefix(path + "invalid/")),
        ),
      )
    }

  lazy val all: Seq[Entry] = Resources.map.keySet().asScala.toSeq
    .filter(_.endsWith("model.yaml"))
    .map(_.stripSuffix("model.yaml"))
    .map(Entry(_))

  // TODO: generate this automatically maybe
  val `abstract`: Entry = Entry("/models/abstract/")
  val aliases: Entry = Entry("/models/aliases/")
  val anything: Entry = Entry("/models/anything/")
  val multivaluedReference: Entry = Entry("/models/multivaluedReference/")
  val basic: Entry = Entry("/models/basic/")
  val basic2: Entry = Entry("/models/basic2/")
  val curie: Entry = Entry("/models/curie/")
  val mixin: Entry = Entry("/models/mixin/")
  val reference: Entry = Entry("/models/reference/")
  val inheritance: Entry = Entry("/models/inheritance/")
  val uri: Entry = Entry("/models/uri/")
  val uriOrCurie: Entry = Entry("/models/uriOrCurie/")
  val emitPrefixes: Entry = Entry("/models/emitPrefixes/")

  object inlines {
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
