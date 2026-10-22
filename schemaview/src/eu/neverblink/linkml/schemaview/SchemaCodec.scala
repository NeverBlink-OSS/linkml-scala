package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{Codec, Prefix, Prefixes, SchemaDefinition}
import eu.neverblink.linkml.runtime.{PrefixMap, named, simpleDict}
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
  */
object SchemaCodec {

  /** Decode a schema, expanding CURIEs with its prefixes.
    *
    * @throws eu.neverblink.linkml.yaml.DecodeError
    *   If the node is not a valid schema
    */
  def decode(node: Node): SchemaDefinition = {
    val declared = prefixesCodec.decode(node)
    Codec.codec.decode(node, prefixes = prefixMap(declared.prefixes, declared.defaultCuriMaps))
  }

  /** Encode a schema, compacting URIs with its prefixes.
    *
    * @param fullUris
    *   Write URIs in full instead
    */
  def encode(schema: SchemaDefinition, fullUris: Boolean = false): Node = {
    val prefixes = prefixMap(schema.prefixes, schema.defaultCuriMaps)
    Codec.codec.encode(schema, prefixes = if (fullUris) prefixes.withoutCompaction else prefixes)
  }

  /** Get the prefixes of a schema, in the order of precedence given above.
    *
    * @throws java.net.URISyntaxException
    *   If a namespace in the schema's `prefixes` is not a valid URI
    */
  def prefixMap(schema: SchemaDefinition): PrefixMap = {
    schema.prefixes.values.foreach(p => new java.net.URI(p.prefixReference.original))
    prefixMap(schema.prefixes, schema.defaultCuriMaps)
  }

  /** Get the prefixes of a schema from its `prefixes` and `default_curi_maps`. */
  private def prefixMap(prefixes: Map[String, Prefix], defaultCuriMaps: Seq[String]): PrefixMap =
    PrefixMap(
      overrides = prefixes.values.map(p => (p.prefixPrefix, p.prefixReference.original)),
      defaults =
        if (defaultCuriMaps.contains("semweb_context")) semwebContextPrefixes
        else metamodelPrefixes,
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
  )

  private val prefixesCodec: LinkmlYamlCodec[DeclaredPrefixes] =
    LinkmlYamlCodec.derived(extraSlotsAllowed = true)
}
