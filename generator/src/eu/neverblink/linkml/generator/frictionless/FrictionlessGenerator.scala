package eu.neverblink.linkml.generator.frictionless

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, WriterConfig, writeToString}
import eu.neverblink.linkml.generator.JsonDocumentGenerator
import eu.neverblink.linkml.generator.frictionless.FieldDescriptor.types
import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.schemaview.*
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.LanguageTag

import scala.collection.mutable

/** Generator for [[https://specs.frictionlessdata.io/data-package/ Frictionless Data Packages]].
  *
  * Every class becomes a CSV table: a resource in `datapackage.json` plus the Table Schema
  * describing its columns. Slots whose range is a type or an enum become columns directly. Slots
  * whose range is another class become either a JSON-valued column (when the range is inlined) or a
  * column holding the target's identifier, backed by a foreign key to the target's table.
  *
  * This generator has two entrypoints:
  *   - [[generate]] builds one self-contained descriptor with every table schema inlined, which is
  *     what you want on stdout or in a single file.
  *   - [[generateFiles]] splits it into `datapackage.json` plus one `schemas/<name>.json` per
  *     table.
  *
  * The CSVs the resources point at do not exist yet.
  */
class FrictionlessGenerator(using sv: SchemaView)
    extends JsonDocumentGenerator[FrictionlessGenerator.Options, DataPackageDescriptor] {

  import FrictionlessGenerator.*

  override protected def defaultOptions: Options = Options()

  override protected def codec: JsonValueCodec[DataPackageDescriptor] =
    DataPackageDescriptor.codec

  override protected def writerConfig(options: Options): WriterConfig =
    WriterConfig.withIndentionStep(2)

  /** Map the [[RuntimeType]] to the appropriate Table Schema (type, format) tuple
    */
  private def remapType(rt: RuntimeType): (String, String) = rt match {
    case _: StringType.type => (types.string, "default")
    case _: IntegerType.type => (types.integer, "default")
    case _: FloatType.type => (types.number, "default")
    case _: DoubleType.type => (types.number, "default")
    case _: BooleanType.type => (types.boolean, "default")
    case _: DecimalType.type => (types.number, "default")
    case _: AnyType.type => (types.any, "default")
    // `default` is ISO 8601, which is what these LinkML types already are. The `any` format would
    // also let through things like 01/02/2020, which the model does not allow.
    case _: DateType.type => (types.date, "default")
    case _: DateTimeType.type => (types.datetime, "default")
    case _: TimeType.type => (types.time, "default")
    case _: UriOrCurieType.type => (types.string, "default")
    case _: UriType.type => (types.string, "uri")
    case _: CurieType.type => (types.string, "default")
    case _: NcNameType.type => (types.string, "default")
    case _: LocalizedTextType.type => (types.string, "default") // TODO LNK-195
    case _: UnknownType.type => (types.any, "default")
  }

  /** Get the name of the slot, respecting alias, and LinkML casing rules
    */
  def slotName(slotView: SlotView): String =
    slotView.slot.alias.getOrElseFast(Case.escaped(slotView.slot.name))

  /** The classes that will be rendered as tables. */
  private def tables(options: Options): Seq[Table] = {
    val query = options.pruningMode.derivedQuery(false, true)
    val treeRoot = sv.treeRootWithOverride(options.pruningMode match {
      case PruningMode.treeRoot(o) => o
      case _ => None
    }).get

    val classes = sv.classes.values
      .filter(cv => query.reachable(cv) && !cv.isAny)
      // The tree root is always a table, even when it is abstract or has no identifier.
      // Every other class has to be non-abstract and have an identifier.
      // Without identifiers, we cannot reference the table.
      .filter(cv =>
        treeRoot.contains(cv) || (
          !cv.cls.`abstract` && !cv.cls.mixin &&
            (!options.skipClassesWithoutIdentifier || cv.hasIdentifier)
        ),
      )
      .toSeq
      .sortBy(_.aliasedName)

    val used = mutable.Set.empty[String]
    classes.map { cv =>
      val base = slug(cv.aliasedName)
      var name = base
      var n = 2
      // Class names are unique, but resource names are lowercase-only, so two classes can slug
      // down to the same string. Whichever sorts second gets a suffix.
      // TODO LNK-159: this should be a bijection
      while used.contains(name) do {
        name = s"$base-$n"
        n += 1
      }
      used += name
      Table(cv, name)
    }
  }

  /** Build the Table Schema for one class.
    *
    * @param cv
    *   The class to describe.
    * @param resources
    *   Map of class name -> resource name, for pointing foreign keys at the other tables. A
    *   reference to a class that is not a table in this package simply gets no foreign key.
    * @return
    *   The Table Schema (Table Descriptor) for [[cv]].
    */
  def tableSchema(cv: ClassView, resources: Map[String, String] = Map.empty)(using
      options: FrictionlessGenerator.Options,
  ): TableDescriptor = {
    val foreignKeys = mutable.ListBuffer.empty[ForeignKey]

    // TODO LNK-198: factor this out
    val fields =
      for av <- cv.attributeViews.values.toSeq
          .sortBy(av => (av.slotView.slot.rank.getOrElseFast(Int.MaxValue), av.slotView.slot.name))
      yield {
        val slotView = av.slotView
        val name = slotName(slotView)
        val base = FieldDescriptor(
          name = name,
          title = slotView.slot.title.flatMapFast(_.inLanguage(options.metadataLanguage)),
          description =
            slotView.slot.description.flatMapFast(_.inLanguage(options.metadataLanguage)),
          constraints = new Some(new Constraints(required = new Some(slotView.slot.required))),
        )
        av match {
          case _: AnyView =>
            base.copy(`type` = types.any)

          case inline: ClassInlineAttributeView =>
            val kind = inline.inlineType match {
              case InlineType.list => types.array
              // plain is JSON objects, optional is JSON object or null, dict inlines are objects
              case _ => types.`object`
            }
            base.copy(`type` = kind, rdfType = new Some(inline.classView.uriStr))

          case ref: ClassReferenceAttributeView =>
            if slotView.slot.multivalued then
              // A cell holding several identifiers is a JSON array, and Frictionless cannot key on
              // one, so this gets no foreign key.
              base.copy(`type` = types.array, rdfType = new Some(ref.classView.uriStr))
            else {
              val (type_, format) = remapType(ref.identifierView.typeView.runtimeType)
              for
                resource <- resources.get(ref.classView.name)
                identifier <- ref.classView.identifier
              do
                foreignKeys += ForeignKey(
                  fields = name,
                  reference = ForeignKeyReference(
                    // The spec spells a self-reference as the empty string, not as the table's own
                    // name.
                    resource = if ref.classView.name == cv.name then "" else resource,
                    fields = slotName(identifier),
                  ),
                )
              base.copy(`type` = type_, rdfType = new Some(ref.classView.uriStr), format = format)
            }

          case tv: TypeAttributeView =>
            if slotView.slot.multivalued then
              base.copy(`type` = types.array, rdfType = new Some(tv.typeView.uriStr))
            else {
              val (type_, format) = remapType(tv.typeView.runtimeType)
              base.copy(
                `type` = type_,
                rdfType = new Some(tv.typeView.uriStr),
                format = format,
                constraints = base.constraints.mapFast(
                  _.copy(
                    pattern = tv.pattern,
                    maximum = tv.maximumValue.mapFast(_.value.strip()),
                    minimum = tv.minimumValue.mapFast(_.value.strip()),
                  ),
                ),
              )
            }

          case ev: EnumAttributeView =>
            if slotView.slot.multivalued then
              // no multivalued enums in table schema...
              base.copy(`type` = types.array, rdfType = new Some(ev.enumView.uriStr))
            else
              base.copy(
                `type` = types.string,
                rdfType = new Some(ev.enumView.uriStr),
                constraints = base.constraints.mapFast(
                  _.copy(`enum` = new Some(ev.enumView.toMeaning.keys.toSeq)),
                ),
              )
        }
      }

    TableDescriptor(
      fields = fields,
      primaryKey = cv.identifier.map(slotName),
      foreignKeys = if foreignKeys.isEmpty then None else new Some(foreignKeys.toSeq),
    )
  }

  /** Generate the data package with every table schema written inline.
    *
    * @param options
    *   What to generate. See [[FrictionlessGenerator.Options]].
    * @return
    *   The whole package as one self-contained descriptor.
    */
  override def generate(options: Options = Options()): DataPackageDescriptor = {
    given Options = options
    val ts = tables(options)
    val byName = resources(ts)
    descriptor(ts, t => SchemaRef.Inline(tableSchema(t.cv, byName)))
  }

  /** Generate the data package split across files: `datapackage.json` at the top, one
    * `schemas/<name>.json` per table below it.
    *
    * @param options
    *   What to generate. See [[FrictionlessGenerator.Options]].
    * @return
    *   Pairs of (path relative to the package directory, file content).
    */
  def generateFiles(options: Options = Options()): Iterable[(String, String)] = {
    given Options = options
    val ts = tables(options)
    val byName = resources(ts)
    val config = writerConfig(options)
    val pkg = descriptor(ts, t => SchemaRef.At(schemaPath(t.resource)))

    ("datapackage.json" -> writeToString(pkg, config)(using DataPackageDescriptor.codec)) +:
      ts.map(t =>
        schemaPath(t.resource) ->
          writeToString(tableSchema(t.cv, byName), config)(using TableDescriptor.codec),
      )
  }

  private def resources(ts: Seq[Table]): Map[String, String] =
    ts.map(t => t.cv.name -> t.resource).toMap

  /** The package descriptor itself. [[schema]] decides whether the table schemas are inlined or
    * split into separate files.
    */
  private def descriptor(ts: Seq[Table], schema: Table => SchemaRef): DataPackageDescriptor = {
    if ts.isEmpty then
      throw RuntimeException(
        "No classes to put in the data package - a data package needs at least one resource. " +
          "Check the pruning mode, and whether every class was skipped for having no identifier.",
      )
    val root = sv.root
    DataPackageDescriptor(
      name = new Some(slug(root.name)),
      id = new Some(root.id.original),
      title = root.title.mapFast(_.plain),
      description = root.description.mapFast(_.plain),
      version = root.version,
      keywords = if root.keywords.isEmpty then None else new Some(root.keywords),
      licenses = root.license.flatMap(license).mapFast(Seq(_)),
      resources = ts.map(t =>
        ResourceDescriptor(
          name = t.resource,
          path = dataPath(t.resource),
          title = t.cv.cls.title.mapFast(_.plain),
          description = t.cv.cls.description.mapFast(_.plain),
          schema = schema(t),
        ),
      ),
    )
  }
}

object FrictionlessGenerator {

  /** A class that was rendered as a table, and the name the for it inside the data package. */
  private final case class Table(cv: ClassView, resource: String)

  /** Options for [[FrictionlessGenerator]].
    *
    * @param pruningMode
    *   Which classes to turn into tables.
    * @param skipClassesWithoutIdentifier
    *   Whether to skip classes that have no identifier slot. Such a table gets no primary key and
    *   nothing can reference it, so it is often not useful.
    * @param metadataLanguage
    *   Which language to use for metadata fields (description) in the generated Table Schema.
    */
  final case class Options(
      pruningMode: PruningMode = PruningMode.skip,
      skipClassesWithoutIdentifier: Boolean = false,
      metadataLanguage: LanguageTag = "en",
  )

  private def schemaPath(resource: String): String = s"schemas/$resource.json"
  private def dataPath(resource: String): String = s"data/$resource.csv"

  /** Open Definition license identifier regex. */
  private val licenseId = "^[-a-zA-Z0-9._]+$".r

  /** Package and resource names must be lowercase alphanumerics, `.`, `-` and `_`. Anything else
    * becomes a hyphen rather than being dropped, so two names cannot silently collapse into one.
    *
    * TODO LNK-159: this should be a bijection and be defined elsewhere to be reusable
    */
  private def slug(raw: String): String = {
    val cleaned = raw.toLowerCase.map {
      case c if c >= 'a' && c <= 'z' => c
      case c if c >= '0' && c <= '9' => c
      case c @ ('.' | '-' | '_') => c
      case _ => '-'
    }
    if cleaned.isEmpty then "table" else cleaned
  }

  /** LinkML's `license` is free text. The data package profile is much pickier, so we check if the
    * license matches something that Frictionless can understand.
    */
  private def license(raw: String): Option[License] = {
    val value = raw.strip()
    if value.startsWith("http://") || value.startsWith("https://") then
      Some(License(path = Some(value)))
    else if licenseId.matches(value) then Some(License(name = Some(value)))
    else None
  }
}
