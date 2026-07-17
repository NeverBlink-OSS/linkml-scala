package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.SchemaView.*

import scala.annotation.unused
import scala.collection.mutable
import scala.compiletime.erasedValue
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** SchemaView is a wrapper class around a metamodel-generated [[SchemaDefinition]], which
  * implements the semantics of the metamodel, such as: references, schema-level default values,
  * imports, inheritance and schema derivation.
  * @param schemas
  *   The schema definitions to be used. The first schema in the sequence is considered the "main"
  *   schema. Schemas earlier in the list shadow definitions from later schemas.
  */
final case class SchemaView(schemas: Seq[SchemaDefinition]) extends ReferenceResolver {
  given SchemaView = this

  if schemas.isEmpty then
    throw IllegalArgumentException("At least one schema definition must be provided to SchemaView")

  /** The main schema definition (root).
    */
  def root: SchemaDefinition = schemas.head

  inline def resolve[T](inline ref: Reference[T]): Option[T] =
    (inline erasedValue[T] match {
      case _: TypeDefinition => types.get(ref.value).map(_._type)
      case _: ClassDefinition => classes.get(ref.value).map(_.cls)
      case _: EnumDefinition => enums.get(ref.value).map(_._enum)
      case _: SubsetDefinition => subsets.get(ref.value).map(_.subset)
      // `range` slot's `range` is underspecified as per the metamodel notes,
      // I think it should be ClassDef | TypeDef | EnumDef
      case _: Element => getElement(ref.value).map(_.inner)
      // And now... element views! (sigh)
      // You can cast the argument of this method to get a view instead of the raw definition.
      // I tried to make it nicer, but the Scala compiler said "no".
      case _: TypeView => types.get(ref.value)
      case _: ClassView => classes.get(ref.value)
      case _: EnumView => enums.get(ref.value)
      case _: SlotView => slotDefinitions.get(ref.value)
      case _: SubsetView => subsets.get(ref.value)
      case _: ElementView[?] => getElement(ref.value)
      case _ => compiletime.error("SchemaView can't dereference " + compiletime.codeOf(ref))
    }).asInstanceOf[Option[T]]

  /** All types defined in the loaded schemas, as views.
    */
  lazy val types: Map[String, TypeView] =
    schemas.foldLeft(Map.newBuilder[String, TypeView]) { (acc, schema) =>
      schema.types.foreach((k, v) => acc.addOne((k, TypeView(v, schema))))
      acc
    }.result()

  /** All slots defined in the loaded schemas, as views.
    */
  lazy val slotDefinitions: Map[String, SlotView] =
    schemas.foldLeft(Map.newBuilder[String, SlotView]) { (acc, schema) =>
      schema.slotDefinitions.foreach((k, v) => acc.addOne((k, SlotView(v, schema))))
      acc
    }.result()

  /** All classes defined in the loaded schemas, as views.
    */
  lazy val classes: Map[String, ClassView] =
    schemas.foldLeft(Map.newBuilder[String, ClassView]) { (acc, schema) =>
      schema.classes.foreach((k, v) => acc.addOne((k, ClassView(v, schema))))
      acc
    }.result()

  /** All enums defined in the loaded schemas, as views.
    */
  lazy val enums: Map[String, EnumView] =
    schemas.foldLeft(Map.newBuilder[String, EnumView]) { (acc, schema) =>
      schema.enums.map((k, v) => acc.addOne((k, EnumView(v, schema))))
      acc
    }.result()

  /** All subsets defined in the loaded schemas, as views.
    */
  lazy val subsets: Map[String, SubsetView] =
    schemas.foldLeft(Map.newBuilder[String, SubsetView]) { (acc, schema) =>
      schema.subsets.map((k, v) => acc.addOne((k, SubsetView(v, schema))))
      acc
    }.result()

  lazy val elements: Map[String, ElementView[? <: Element]] =
    subsets ++ slotDefinitions ++ enums ++ types ++ classes

  /** Cached prefix resolvers for each schema in the view.
    *
    * These should be used in ElementView instead of creating a new prefix resolver every time.
    */
  lazy val prefixResolvers: Map[SchemaDefinition, BasicPrefixResolver] =
    schemas.map(schema => schema -> createPrefixResolver(schema)).toMap

  /** Get all classes reachable from a given class, following derived attributes and optionally
    * ancestors. The result is a map of class name to class view, including the starting class.
    *
    * This is useful for generators that want to prune the schema to only the relevant classes
    * reachable from a given root.
    *
    * @param from
    *   The class to start from
    * @param includeAncestors
    *   Whether to include ancestors of the found classes in the result. If false, only the classes
    *   directly reachable via slots will be included.
    * @param inlinedOnly
    *   Whether to include only classes that are inlined in the slots where they are used. If false,
    *   all reachable classes will be included regardless of whether they are inlined or not.
    * @todo
    *   LNK-111 Merge with [[reachableFrom()]]
    */
  def classesReachableFrom(
      from: ClassView,
      includeAncestors: Boolean,
      inlinedOnly: Boolean,
  ): Map[String, ClassView] =
    val agenda = mutable.Queue(from)
    val found = mutable.Map.empty[String, ClassView]
    while agenda.nonEmpty do
      val current = agenda.dequeue()
      if !found.contains(current.cls.name) then
        found.put(current.cls.name, current)
        if includeAncestors then for c <- current.ancestors(reflexive = false) do agenda.enqueue(c)
        for (_, slot) <- current.derivedAttributes do
          if !inlinedOnly || slot.derivedInlined then
            slot.derivedRangeView.resolve match {
              case Some(cls: ClassView) => agenda.enqueue(cls)
              case _ =>
            }
    found.toMap

  /** Find all [[Element]]s that are reachable from the [[fromClasses]]. Also includes types, enums,
    * slots.
    *
    * @param fromClasses
    *   Class definition(s) to start the reachability query from.
    * @param derivedClasses
    *   If true, will only consider `derivedAttributes` for class derivation. This is in-line with
    *   what [[ClassView.materialize]] will clear. If false, will instead mark inheritance-related
    *   slots as reachable.
    *
    * @todo
    *   Make this search more robust (LNK-110). Currently, this will prune things incorrectly if
    *   there are any boolean slots (like `any_of`)
    *
    * @todo
    *   LNK-111 Clean this up and merge with [[classesReachableFrom()]]
    *
    * @return
    *   A set of elements reachable from [[rootClass]] and their [[ElementTypeTag]]s
    */
  def reachableFrom(
      fromClasses: Seq[ClassDefinition],
      derivedClasses: Boolean,
  ): Set[(ElementTypeTag, Element)] =
    Closure.reflexive[(ElementTypeTag, Element)](
      fromClasses.map(ElementTypeTag.classDef -> _),
      el => {
        val elements: Iterable[Element] = el._2 match {
          case cls: ClassDefinition =>
            if !derivedClasses then classes(cls.name).derivedAttributes.map(_._2.slot)
            else
              (cls.slots ++ cls.isA ++ cls.mixins).flatMap(_.resolve)
                ++ cls.attributes.values ++ cls.slotUsage.values
          case typeDefinition: TypeDefinition =>
            (typeDefinition.typeof ++ typeDefinition.unionOf).flatMap(_.resolve)
          case enumDefinition: EnumDefinition =>
            enumDefinition.inherits.flatMap(_.resolve)
          case slotDefinition: SlotDefinition =>
            val inherited = slotDefinition.isA ++ slotDefinition.mixins
            (slotDefinition.anyOf.flatMap(
              _.range,
            ) ++ slotDefinition.range ++ slotDefinition.domain ++ (
              if derivedClasses then inherited
              else Seq.empty
            )).flatMap(_.resolve)

          case _ => Seq.empty
        }

        elements.map(el => ElementTypeTag(el) -> el)
      },
    ).toSet

  /** Get a schema element by its ID
    */
  def getElement(name: String): Option[ElementView[?]] = elements.get(name)

  /** Get the class defined as `tree_root: true` from the schema, if any is present
    */
  def treeRoot: Option[ClassView] =
    // We check only the root schema for tree root definitions.
    classes.values.find(c => c.cls.treeRoot && c.definingSchema == root)

  /** Get the class to be used as the tree root, either from the `tree_root` field in the schema or
    * from the provided override. If both are present, the override takes precedence. If the
    * override is provided but does not resolve to a valid class definition, a Failure is returned.
    *
    * If no override is specified, this method behaves the same as [[treeRoot]], returning an
    * `Option[ClassView]`.
    */
  def treeRootWithOverride(treeRootOverride: Option[String]): Try[Option[ClassView]] =
    treeRootOverride match {
      case Some(className) =>
        classes.get(className) match {
          case Some(cls) => Success(Some(cls))
          case _ =>
            val msg = s"Could not find class '$className' defined as the tree root override"
            Failure(RuntimeException(msg))
        }
      case _ => Try(treeRoot)
    }

  /** Apply `slot_usage` and `attributes` for a class and then its ancestors, with mixins having
    * priority.
    *
    * @see
    *   `ApplySlotUsage` from
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-calculate-derived-slot
    */
  private[schemaview] def applySlotUsage(
      slot: SlotDefinitionImpl,
      slotName: String,
      cls: ClassDefinition,
  ): SlotDefinitionImpl = {
    var currentSlot = slot
    def combine(s: SlotDefinitionImpl): Unit =
      currentSlot = currentSlot.combineWith(s, combineRange)
    cls.slotUsage.get(slotName).foreach(combine)
    cls.attributes.get(slotName).foreach(combine)
    for c <- cls.mixins.flatMap(resolve) do currentSlot = applySlotUsage(currentSlot, slotName, c)
    for c <- cls.isA.flatMap(resolve) do currentSlot = applySlotUsage(currentSlot, slotName, c)
    currentSlot
  }

  /** Combine values for the `range` metaslot
    */
  // TODO COMPAT
  private[schemaview] def combineRange(
      v1: Reference[Element],
      @unused v2: Reference[Element],
  ): Reference[Element] = v1

  val rootPrefixResolver: BasicPrefixResolver = createPrefixResolver(root)
  private val validator = SchemaValidator()

  {
    val problems = validator.fatalProblems
    if (problems.nonEmpty) {
      val formatted = SchemaProblem.format(
        problems,
        maxProblems = 5,
        verbose = true,
        showLevel = false,
      )
      sys.error(s"Fatal validation problems:\n$formatted")
    }
  }

  /** Whether the merged schema is valid */
  lazy val isValid: Boolean = validator.validate().isSuccess

  /** Validate the merged schema, checking for errors and fatal errors
    *
    * @param maxProblems
    *   Max number of problems to include
    * @return
    *   Unit if the schema is valid, an exception with formatted problems otherwise
    */
  def validate(maxProblems: Int = 5): Try[Unit] = validator.validate(maxProblems)

  /** Produce validation report with all detected problems
    *
    * @param maxProblems
    *   Max number of problems to include in the report
    * @param verbose
    *   Whether to use the longer, more descriptive problem description
    * @return
    *   A validation report if there are any problems to report, None otherwise
    */
  def lint(maxProblems: Int = 5, verbose: Boolean = false): Option[String] =
    validator.lint(maxProblems, verbose)
}

object SchemaView {

  /** Shorthand for creating a SchemaView with a single schema definition. Mainly for testing, this
    * does not resolve imports!
    *
    * This is deliberately not called apply() to avoid confusion with the constructor that expects
    * to get a list of already resolved imports.
    *
    * @param schema
    *   Schema definition to create the view from
    */
  def single(schema: SchemaDefinition): SchemaView = new SchemaView(Seq(schema))

  extension (schema: SchemaDefinition)

    /** Get the default range for the model, with the `string` type fallback as specified in the
      * spec.
      *
      * @see
      *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#rule-populate-schema-metadata
      */
    def defaultRangeResolved: Reference[Element] =
      schema.defaultRange.getOrElse(Reference[TypeDefinition]("string"))

  /** Loads a schema view from the specified URI, loading its imports.
    *
    * @param uri
    *   The URI of the schema to load. This can be a URL starting with "https://", "http://", or a
    *   file path.
    * @param importer
    *   An importer of schema imports. Default is [[FileSystemImporter]] that reads from the file
    *   system.
    * @return
    *   The schema view loaded from the specified URI.
    */
  def loadSchemaViewFromUri(
      uri: String,
      importer: Importer = FileSystemImporter,
  ): SchemaView = new SchemaView(loadSchemas(uri, importer))

  /** Loads a schema view from the specified YAML string, loading its imports. This is mainly for
    * testing and custom applications, as in most cases you would want to load from a URI to get
    * proper relative import resolution.
    *
    * @param yaml
    *   Schema definition as a serialized YAML string
    * @param importer
    *   An importer of schema imports. Default is [[FileSystemImporter]] that reads from the file
    *   system. Note that the importer will be used with an empty base URI, so it should be able to
    *   handle absolute URIs in imports, or you should provide a custom importer that can handle
    *   them.
    * @return
    */
  def loadSchemaViewFromString(
      yaml: String,
      importer: Importer = FileSystemImporter,
  ): SchemaView = {
    val root = importer.parseSchema(yaml)
    new SchemaView(root +: loadImports(root, "", importer))
  }

  /** Loads individual schema definitions from the specified URI, optionally loading their imports.
    * Import loading is recursive.
    *
    * @param uri
    *   The URI of the schema to load. This can be a URL starting with "https://", "http://", or a
    *   file path.
    * @param doImportLoading
    *   A boolean flag indicating whether to load the imports of the schema. Defaults to true.
    * @param importer
    *   An importer of schema imports. Default is [[FileSystemImporter]] that reads from the file
    *   system.
    * @return
    *   The sequence of schema definitions loaded from the specified URI, with the main schema first
    *   followed by imports in the order they are declared in the schema, with imports of imports
    *   following the same pattern.
    */
  def loadSchemas(
      uri: String,
      importer: Importer = FileSystemImporter,
  ): Seq[SchemaDefinition] =
    loadSchemasInternal(uri, true, importer, mutable.Set.empty)

  private def loadSchemasInternal(
      uri: String,
      doImportLoading: Boolean,
      importer: Importer,
      visited: mutable.Set[String],
  ): Seq[SchemaDefinition] = {
    var normalizedUri = uri.stripSuffix(PlatformSpecificUtils.separator)
    if (!normalizedUri.endsWith(".yaml") && !normalizedUri.endsWith(".yml"))
      normalizedUri += ".yaml"
    // After URI normalization, check if we've already visited this URI to avoid infinite loops
    // and repeatedly loading the same schema.
    if visited.contains(normalizedUri) then Seq()
    else
      visited.add(normalizedUri)
      val schema: SchemaDefinition =
        if (normalizedUri.startsWith("https://w3id.org/linkml/")) {
          importer.parseSchema(Resources.read(normalizedUri.stripPrefix("https://w3id.org/linkml")))
        } else if (normalizedUri.startsWith("linkml:")) {
          importer.parseSchema(Resources.read("/" + normalizedUri.stripPrefix("linkml:")))
        } else {
          try importer.readSchema(normalizedUri)
          catch {
            case ex if NonFatal(ex) =>
              sys.error(s"Cannot import schema '$normalizedUri'\n" + ex.getMessage)
          }
        }
      if (doImportLoading) {
        var baseUri = ""
        val idx = normalizedUri.lastIndexOf(PlatformSpecificUtils.separator)
        if (idx > 0) baseUri = normalizedUri.substring(0, idx)
        schema +: loadImportsInternal(schema, baseUri, importer, visited)
      } else Seq(schema)
  }

  /** Loads a schema's imports from the specified schema, loading its imports recursively from the
    * provided importer.
    *
    * @param schema
    *   The schema with some imports.
    * @param baseUri
    *   This can be a URL starting with "https://", "http://", or a file path.
    * @param importer
    *   An importer of schema imports. Default is [[FileSystemImporter]] that reads from the file
    *   system.
    * @return
    *   The schema definition combined with loaded imports.
    */
  def loadImports(
      schema: SchemaDefinition,
      baseUri: String = "",
      importer: Importer = FileSystemImporter,
  ): Seq[SchemaDefinition] =
    loadImportsInternal(schema, baseUri, importer, mutable.Set.empty)

  private def loadImportsInternal(
      schema: SchemaDefinition,
      baseUri: String,
      importer: Importer,
      visited: mutable.Set[String],
  ): Seq[SchemaDefinition] = {
    given PrefixResolver = createPrefixResolver(schema)
    schema.imports.flatMap { uoc =>
      var sUri = uoc.uri.stripPrefix("./")
      if (baseUri.nonEmpty && !sUri.contains("://") && !sUri.startsWith("urn:"))
        sUri = baseUri + PlatformSpecificUtils.separator + sUri
      loadSchemasInternal(sUri, true, importer, visited)
    }
  }

  enum ElementTypeTag:
    case classDef, typeDef, slotDef, enumDef, other

  object ElementTypeTag:
    def apply(el: Element): ElementTypeTag = el match {
      case _: ClassDefinition => classDef
      case _: TypeDefinition => typeDef
      case _: SlotDefinition => slotDef
      case _: EnumDefinition => enumDef
      case _ => other
    }

  /** Create a [[BasicPrefixResolver]] based on the given schema. Loads metamodel emit_prefixes,
    * resolves "semweb_context" curi map and loads user defined prefixes.
    */
  def createPrefixResolver(forSchema: SchemaDefinition): BasicPrefixResolver = {
    val prefixResolver = new BasicPrefixResolver(forSchema.id.original)
    Prefixes.map.foreach { (prefix, uri) => prefixResolver.add(prefix, uri) }
    if (forSchema.defaultCuriMaps.contains("semweb_context")) {
      Array(
        ("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
        ("rdfs", "http://www.w3.org/2000/01/rdf-schema#"),
        ("owl", "http://www.w3.org/2002/07/owl#"),
        ("xsd", "http://www.w3.org/2001/XMLSchema#"),
        ("dc", "http://purl.org/dc/terms/"),
        ("dcterms", "http://purl.org/dc/terms/"),
        ("faldo", "http://biohackathon.org/resource/faldo#"),
        ("foaf", "http://xmlns.com/foaf/0.1/"),
        ("oa", "http://www.w3.org/ns/oa#"),
        ("idot", "http://identifiers.org/"),
        ("void", "http://rdfs.org/ns/void#"),
        ("prov", "http://www.w3.org/ns/prov#"),
        ("dcat", "http://www.w3.org/ns/dcat#"),
      ).foreach { case (prefix, uri) =>
        prefixResolver.add(prefix, uri)
      }
    }

    forSchema.prefixes.values.foreach(prefix =>
      prefixResolver.add(prefix.prefixPrefix, prefix.prefixReference.original),
    )

    prefixResolver
  }
}
