package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview
import eu.neverblink.linkml.schemaview.SchemaView.*
import eu.neverblink.linkml.validation.{
  IssueLocationImpl,
  SchemaError,
  SchemaFatal,
  UnexpectedErrorImpl,
}

import scala.annotation.unused
import scala.collection.{immutable, mutable}
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
      case _: TypeDefinition =>
        types.get(ref.value) match {
          case Some(tv) => new Some(tv._type)
          case _ => None
        }
      case _: ClassDefinition =>
        classes.get(ref.value) match {
          case Some(cv) => new Some(cv.cls)
          case _ => None
        }
      case _: EnumDefinition =>
        enums.get(ref.value) match {
          case Some(ev) => new Some(ev._enum)
          case _ => None
        }
      case _: SubsetDefinition =>
        subsets.get(ref.value) match {
          case Some(sv) => new Some(sv.subset)
          case _ => None
        }
      // `range` slot's `range` is underspecified as per the metamodel notes,
      // I think it should be ClassDef | TypeDef | EnumDef
      case _: Element =>
        elements.get(ref.value) match {
          case Some(ev) => new Some(ev.inner)
          case _ => None
        }
      // And now... element views! (sigh)
      // You can cast the argument of this method to get a view instead of the raw definition.
      // I tried to make it nicer, but the Scala compiler said "no".
      case _: TypeView => types.get(ref.value)
      case _: ClassView => classes.get(ref.value)
      case _: EnumView => enums.get(ref.value)
      case _: SlotView => slotDefinitions.get(ref.value)
      case _: SubsetView => subsets.get(ref.value)
      case _: ElementView[?, ?] => elements.get(ref.value)
      case _ => compiletime.error("SchemaView can't dereference ".concat(compiletime.codeOf(ref)))
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

  lazy val elements: Map[String, ElementView[? <: Element, ?]] =
    immutable.HashMap.newBuilder[String, ElementView[? <: Element, ?]]
      .addAll(subsets)
      .addAll(slotDefinitions)
      .addAll(enums)
      .addAll(types)
      .addAll(classes)
      .result()

  /** Cached prefix resolvers for each schema in the view.
    *
    * These should be used in ElementView instead of creating a new prefix resolver every time.
    */
  private lazy val prefixResolvers: Map[Uri, BasicPrefixResolver] =
    schemas.foldLeft(Map.newBuilder[Uri, BasicPrefixResolver]) { (acc, schema) =>
      acc.addOne((schema.id, createPrefixResolver(schema)))
    }.result()

  def getPrefixResolver(schema: SchemaDefinition): BasicPrefixResolver =
    prefixResolvers(schema.id)

  private val defaultRange = new Reference[TypeView]("string")

  /** Get the default range for the model, with the `string` type fallback as specified in the spec.
    *
    * @see
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#rule-populate-schema-metadata
    */
  def getDefaultRange(schema: SchemaDefinition): Reference[TypeView] = schema.defaultRange match {
    case Some(r) => r.asInstanceOf[Reference[TypeView]]
    case _ => defaultRange
  }

  /** Get the default URI prefix (prefix map value) for the schema, with a fallback to the schema ID
    * (this fallback mirrors the python implementation).
    */
  def getDefaultPrefix(schema: SchemaDefinition): String = {
    val prefixResolver = prefixResolvers(schema.id)
    val prefixRef = schema.defaultPrefix match {
      case Some(prefix) => schema.prefixes.get(prefix)
      case _ => None
    }
    prefixRef match {
      case Some(ref) => ref.prefixReference.uri(using prefixResolver)
      case _ =>
        val uri = schema.id.uri(using prefixResolver)
        val len = uri.length
        if (len > 0) {
          val ch = uri.charAt(len - 1)
          if (ch == '#' || ch == '/') uri
          else uri.concat("/")
        } else "/"
    }
  }

  /** Get all elements reachable from a given starting set, following slots, ranges, inheritance and
    * other reference slots. This will run the query without schema derivation.
    *
    * @param from
    *   Starting set of elements
    */
  def underivedReachabilityQuery(
      from: Seq[ElementView[?, ?]],
  ): UnderivedReachabilityQuery = new UnderivedReachabilityQuery(from)

  /** Get all elements reachable from a given starting set, following derived attributes and other
    * reference slots. This will run the query as-if schema derivation was performed.
    *
    * @see
    *   [[ClassView.materialize]] - this query will allow correct pruning for the return values of
    *   this method
    *
    * @param from
    *   Starting set of elements
    * @param inlinedOnly
    *   If true, will exclude by-reference class ranges when computing reachability.
    * @param includeClassAncestors
    *   If true, will include class' ancestors when computing reachability.
    */
  def derivedReachabilityQuery(
      from: Seq[ElementView[?, ?]],
      inlinedOnly: Boolean,
      includeClassAncestors: Boolean,
  ): DerivedReachabilityQuery =
    new DerivedReachabilityQuery(from, inlinedOnly, includeClassAncestors)

  /** Get a schema element by its ID
    */
  def getElement(name: String): Option[ElementView[?, ?]] = elements.get(name)

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
    new Success(treeRootOverride match {
      case Some(className) =>
        val optCv = classes.get(className)
        if (optCv eq None) {
          val msg = s"Could not find class '$className' defined as the tree root override"
          return new Failure(new RuntimeException(msg))
        } else optCv
      case _ => treeRoot
    })

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
    cls.slotUsage.get(slotName) match {
      case Some(s) => currentSlot = currentSlot.combineWith(s, combineRange)
      case _ =>
    }
    cls.attributes.get(slotName) match {
      case Some(s) => currentSlot = currentSlot.combineWith(s, combineRange)
      case _ =>
    }
    cls.mixins.foreach { r =>
      resolve(r) match {
        case Some(c) => currentSlot = applySlotUsage(currentSlot, slotName, c)
        case _ =>
      }
    }
    cls.isA match {
      case Some(r) =>
        resolve(r) match {
          case Some(c) => currentSlot = applySlotUsage(currentSlot, slotName, c)
          case _ =>
        }
      case _ =>
    }
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
  private val validator = new SchemaValidator()

  {
    val problems = validator.fatalProblems
    if (problems.nonEmpty) {
      throw SchemaIssues.FatalSchemaException(problems.map(_.infer()), maxProblems = 5)
    }
  }

  /** Whether the merged schema is valid */
  lazy val isValid: Boolean = validator.validationProblems.isEmpty

  /** Errors and fatal problems in the merged schema, empty if the schema is valid. Warnings are not
    * included - use [[lint]] for a report that covers those too.
    */
  def validationProblems: Seq[SchemaError | SchemaFatal] = validator.validationProblems

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
  ): Either[Seq[SchemaFatal], SchemaView] =
    guarded(loadSchemas(uri, importer).left.map(Seq(_)).flatMap(viewOf))

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
  ): Either[Seq[SchemaFatal], SchemaView] =
    guarded(
      importer.parseSchema(yaml)
        .flatMap(root => loadImports(root, "", importer).map(root +: _))
        .left.map(Seq(_))
        .flatMap(viewOf),
    )

  /** Run a load, reporting anything it throws as an
    * [[eu.neverblink.linkml.validation.UnexpectedError]].
    */
  private def guarded(
      load: => Either[Seq[SchemaFatal], SchemaView],
  ): Either[Seq[SchemaFatal], SchemaView] =
    try load
    catch { case NonFatal(ex) => Left(Seq(unexpectedError(ex))) }

  private def unexpectedError(ex: Throwable): UnexpectedErrorImpl =
    UnexpectedErrorImpl(
      location = IssueLocationImpl(),
      reason = Option(ex.getMessage).getOrElse(ex.toString),
    )

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
  ): Either[ImportFailure, Seq[SchemaDefinition]] =
    loadSchemasInternal(uri, true, importer, mutable.Set.empty)

  /** Build a view from already-loaded schemas, turning the constructor's fatal validation problems
    * into a Left rather than an exception.
    */
  private def viewOf(schemas: Seq[SchemaDefinition]): Either[Seq[SchemaFatal], SchemaView] =
    try Right(SchemaView(schemas))
    catch {
      case ex: SchemaIssues.FatalSchemaException => Left(ex.problems)
      case NonFatal(ex) => Left(Seq(unexpectedError(ex)))
    }

  /** Load one of the schemas bundled as a resource, reporting a missing resource as an import
    * failure rather than letting the resource lookup throw.
    */
  private def builtIn(
      uri: String,
      resource: String,
      importer: Importer,
  ): Either[ImportFailure, SchemaDefinition] =
    Importer.readText(uri)(Resources.read(resource)) match {
      case Right(text) => importer.parseSchema(text, uri)
      case Left(failure) => Left(failure)
    }

  private def loadSchemasInternal(
      uri: String,
      doImportLoading: Boolean,
      importer: Importer,
      visited: mutable.Set[String],
  ): Either[ImportFailure, Seq[SchemaDefinition]] = {
    // TODO LNK-154 Robust file system importing
    var normalizedUri = uri.stripSuffix(PlatformSpecificUtils.separator)
    if (!normalizedUri.endsWith(".yaml") && !normalizedUri.endsWith(".yml")) {
      normalizedUri = normalizedUri.concat(".yaml")
    }
    // After URI normalization, check if we've already visited this URI to avoid infinite loops
    // and repeatedly loading the same schema.
    if visited.contains(normalizedUri) then Right(Seq())
    else
      visited.add(normalizedUri)
      // Built-in schemas come from bundled resources, everything else from the importer. Both
      // routes yield the same structured issues on failure.
      val loaded: Either[ImportFailure, SchemaDefinition] =
        if (normalizedUri.startsWith("https://w3id.org/linkml/")) {
          builtIn(normalizedUri, normalizedUri.stripPrefix("https://w3id.org/linkml"), importer)
        } else if (normalizedUri.startsWith("linkml:")) {
          builtIn(normalizedUri, "/" + normalizedUri.stripPrefix("linkml:"), importer)
        } else {
          importer.readSchema(normalizedUri)
        }
      loaded.flatMap { schema =>
        if (doImportLoading) {
          var baseUri = ""
          val idx = normalizedUri.lastIndexOf(PlatformSpecificUtils.separator)
          if (idx > 0) baseUri = normalizedUri.substring(0, idx)
          loadImportsInternal(schema, baseUri, importer, visited).map(schema +: _)
        } else Right(Seq(schema))
      }
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
  ): Either[ImportFailure, Seq[SchemaDefinition]] =
    loadImportsInternal(schema, baseUri, importer, mutable.Set.empty)

  private def loadImportsInternal(
      schema: SchemaDefinition,
      baseUri: String,
      importer: Importer,
      visited: mutable.Set[String],
  ): Either[ImportFailure, Seq[SchemaDefinition]] = {
    given PrefixResolver = createPrefixResolver(schema)
    // Short-circuits on the first import that cannot be loaded.
    schema.imports.foldLeft[Either[ImportFailure, Seq[SchemaDefinition]]](Right(Seq())) {
      (acc, uoc) =>
        acc.flatMap { loadedSoFar =>
          var sUri = uoc.uri.stripPrefix("./")
          if (baseUri.nonEmpty && !sUri.contains("://") && !sUri.startsWith("urn:"))
            sUri = baseUri + PlatformSpecificUtils.separator + sUri
          loadSchemasInternal(sUri, true, importer, visited).map(loadedSoFar ++ _)
        }
    }
  }

  /** Create a [[BasicPrefixResolver]] based on the given schema. Loads metamodel emit_prefixes,
    * resolves "semweb_context" curi map and loads user defined prefixes.
    */
  private def createPrefixResolver(forSchema: SchemaDefinition): BasicPrefixResolver = {
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
