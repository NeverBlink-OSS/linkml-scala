package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{Codec, Prefix, Prefixes, SchemaDefinition}
import eu.neverblink.linkml.runtime.{PrefixMap, UriCurieValidator, UriOrCurie, named, simpleDict}
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.Node

/** Decodes and encodes LinkML schemas with their prefixes.
  *
  * Decoding expands each CURIE in a `uriorcurie` slot whose prefix is known, and encoding compacts
  * each URI that a known namespace matches. So code that works with a decoded schema gets URIs, not
  * CURIEs. A value with an unknown prefix, such as `xsd:string` without an `xsd` prefix, is a URI
  * with that prefix as its scheme.
  *
  * The prefixes of a schema are, in order of precedence:
  *
  *   1. The schema's own `prefixes`.
  *   2. The `semweb_context` prefixes, if the schema lists it in `default_curi_maps`.
  *   3. The prefixes of the LinkML metamodel.
  *
  * The prefixes of imported schemas are never used.
  *
  * A value without a colon, such as `Person`, is put after the namespace of the schema's
  * `default_prefix`, or after the schema's `id` if it has no `default_prefix` (see
  * [[defaultNamespace]]). This isn't done for `imports`, where such a value is a path relative to
  * the importing schema.
  */
object SchemaCodec {

  /** Decode a schema, expanding CURIEs with its prefixes.
    *
    * @throws eu.neverblink.linkml.yaml.DecodeError
    *   If the node is not a valid schema
    */
  def decode(node: Node): SchemaDefinition = {
    val declared = prefixesCodec.decode(node)
    val prefixes = prefixMap(
      declared.prefixes,
      declared.defaultCuriMaps,
      new Some(defaultNamespace(declared.id, declared.defaultPrefix, declared.prefixes)),
    )
    val imports = importsCodec
      .decode(node, prefixes = prefixMap(declared.prefixes, declared.defaultCuriMaps, None))
      .imports
    Codec.codec.decode(node, prefixes = prefixes).copy(imports = imports)
  }

  /** Encode a schema, compacting URIs with its prefixes.
    *
    * @param fullUris
    *   Write URIs in full instead
    */
  def encode(schema: SchemaDefinition, fullUris: Boolean = false): Node = {
    val prefixes = prefixMap(schema)
    Codec.codec.encode(schema, prefixes = if (fullUris) prefixes.withoutCompaction else prefixes)
  }

  /** Get the prefixes of a schema, in the order of precedence given above.
    *
    * @throws IllegalArgumentException
    *   If a namespace in the schema's `prefixes` is not a valid URI. A namespace that is only a
    *   scheme, such as `doi:`, is valid.
    */
  def prefixMap(schema: SchemaDefinition): PrefixMap = {
    schema.prefixes.values.foreach { p =>
      val ns = p.prefixReference.original
      if (!UriCurieValidator.isValidUri(ns))
        throw new IllegalArgumentException(
          s"The namespace '$ns' of the prefix '${p.prefixPrefix}' isn't a valid URI",
        )
    }
    prefixMap(
      schema.prefixes,
      schema.defaultCuriMaps,
      new Some(defaultNamespace(schema.id.original, schema.defaultPrefix, schema.prefixes)),
    )
  }

  /** Get the namespace of a schema's `default_prefix`. Without a `default_prefix`, or if it isn't
    * in the schema's `prefixes`, it is the schema's `id`, with a `/` added if the `id` doesn't end
    * with `/` or `#`, as in LinkML-Python.
    */
  def defaultNamespace(
      id: String,
      defaultPrefix: Option[String],
      prefixes: Map[String, Prefix],
  ): String =
    defaultPrefix.flatMapFast(p => prefixes.get(p)).foldFast {
      val len = id.length
      if (len > 0) {
        val ch = id.charAt(len - 1)
        if (ch == '#' || ch == '/') id
        else id.concat("/")
      } else "/"
    }(_.prefixReference.original)

  /** Get the prefixes of a schema from its `prefixes`, `default_curi_maps` and default namespace.
    */
  private def prefixMap(
      prefixes: Map[String, Prefix],
      defaultCuriMaps: Seq[String],
      defaultNamespace: Option[String],
  ): PrefixMap =
    PrefixMap(
      overrides = prefixes.values.map(p => (p.prefixPrefix, p.prefixReference.original)),
      defaults =
        if (defaultCuriMaps.contains("semweb_context")) semwebContextPrefixes
        else metamodelPrefixes,
      defaultNamespace = defaultNamespace,
    )

  /** The prefixes of the LinkML metamodel. */
  private val metamodelPrefixes: Seq[(String, String)] = Prefixes.map.toSeq

  /** The prefixes of the `semweb_context` CURIE map, together with the metamodel's. */
  private val semwebContextPrefixes: Seq[(String, String)] = metamodelPrefixes ++ Seq(
    "dc" -> "http://purl.org/dc/terms/",
    "faldo" -> "http://biohackathon.org/resource/faldo#",
    "foaf" -> "http://xmlns.com/foaf/0.1/",
    "oa" -> "http://www.w3.org/ns/oa#",
    "idot" -> "http://identifiers.org/",
    "void" -> "http://rdfs.org/ns/void#",
    "prov" -> "http://www.w3.org/ns/prov#",
    "dcat" -> "http://www.w3.org/ns/dcat#",
  )

  /** The slots of a schema that hold its prefixes, read before the rest of the schema. */
  private final case class DeclaredPrefixes(
      @simpleDict prefixes: Map[String, Prefix] = Map(),
      @named("default_curi_maps") defaultCuriMaps: Seq[String] = Nil,
      @named("default_prefix") defaultPrefix: Option[String] = None,
      id: String = "",
  )

  private val prefixesCodec: LinkmlYamlCodec[DeclaredPrefixes] =
    LinkmlYamlCodec.derived(extraSlotsAllowed = true)

  /** The imports of a schema, decoded without a default namespace. */
  private final case class DeclaredImports(imports: Seq[UriOrCurie] = Nil)

  private val importsCodec: LinkmlYamlCodec[DeclaredImports] =
    LinkmlYamlCodec.derived(extraSlotsAllowed = true)
}
