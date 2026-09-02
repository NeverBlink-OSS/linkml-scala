package eu.neverblink.linkml.generator.frictionless

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

/** Frictionless Data Package descriptor model. This is the root of `datapackage.json`.
  *
  * @see
  *   https://specs.frictionlessdata.io/data-package/
  *
  * @param profile
  *   Always `tabular-data-package`: every resource we emit is a CSV table with a Table Schema.
  * @param name
  *   Short, url-usable name of the package. Lowercase alphanumerics, `.`, `-` and `_` only.
  * @param id
  *   Globally unique identifier of the package - the LinkML schema's `id`.
  * @param title
  *   One-sentence description of the package.
  * @param description
  *   Longer, Markdown-formatted description.
  * @param version
  *   Version of the package. The spec asks for Semantic Versioning; LinkML does not, so whatever
  *   the schema declares is passed through as-is.
  * @param keywords
  *   Keywords to help find the package in a catalogue.
  * @param licenses
  *   Licenses the package is made available under.
  * @param resources
  *   The tables. The spec requires at least one.
  */
final case class DataPackageDescriptor(
    profile: String = "tabular-data-package",
    name: Option[String] = None,
    id: Option[String] = None,
    title: Option[String] = None,
    description: Option[String] = None,
    version: Option[String] = None,
    keywords: Option[Seq[String]] = None,
    licenses: Option[Seq[License]] = None,
    resources: Seq[ResourceDescriptor] = Seq(),
)

object DataPackageDescriptor:
  /** @see [[TableDescriptor.codec]] for why the settings are spelled out here again. */
  given codec: JsonValueCodec[DataPackageDescriptor] =
    JsonCodecMaker.make(
      CodecMakerConfig.withEncodingOnly(true)
        .withDiscriminatorFieldName(None)
        .withTransientDefault(false)
        .withTransientEmpty(false),
    )

/** @see
  *   https://specs.frictionlessdata.io/data-package/#licenses
  *
  * @param name
  *   An Open Definition license identifier, e.g. `CC-BY-4.0`.
  * @param path
  *   A URL for the license text.
  */
final case class License(
    name: Option[String] = None,
    path: Option[String] = None,
)

/** A Frictionless Tabular Data Resource: one CSV table and the Table Schema describing it. The CSV
  * at [[path]] does not exist yet. A generated package is a template.
  *
  * @see
  *   https://specs.frictionlessdata.io/tabular-data-resource/
  *
  * @param profile
  *   Always `tabular-data-resource`, as the tabular data package profile requires.
  * @param name
  *   Unique name of the resource within the package. Lowercase alphanumerics, `.`, `-` and `_`.
  * @param path
  *   Relative POSIX path to the CSV. Never absolute and never `../`, which the spec forbids.
  * @param format
  *   Always `csv`.
  * @param mediatype
  *   Always `text/csv`.
  * @param encoding
  *   Always `utf-8`.
  * @param title
  *   Human-readable label, from the class' `title`.
  * @param description
  *   From the class' `description`.
  * @param schema
  *   The Table Schema, either inline or as a path to a separate document. See [[SchemaRef]].
  */
final case class ResourceDescriptor(
    name: String,
    profile: String = "tabular-data-resource",
    path: String = "",
    format: String = "csv",
    mediatype: String = "text/csv",
    encoding: String = "utf-8",
    title: Option[String] = None,
    description: Option[String] = None,
    schema: SchemaRef = SchemaRef.Inline(TableDescriptor()),
)

/** The `schema` property of a resource. The spec allows either the Table Schema itself, inline, or
  * a string pointing at a separate JSON document holding it - and those two are the generator's two
  * output modes.
  *
  * @see
  *   https://specs.frictionlessdata.io/data-resource/#data-in-multiple-files
  */
sealed trait SchemaRef

object SchemaRef:

  /** The table schema written straight into `datapackage.json`. */
  final case class Inline(table: TableDescriptor) extends SchemaRef

  /** A relative path to the table schema, e.g. `schemas/person.json`. */
  final case class At(path: String) extends SchemaRef

  given codec: JsonValueCodec[SchemaRef] = new JsonValueCodec[SchemaRef] {
    def encodeValue(x: SchemaRef, out: JsonWriter): Unit = x match {
      case Inline(table) => TableDescriptor.codec.encodeValue(table, out)
      case At(path) => out.writeVal(path)
    }

    def decodeValue(in: JsonReader, default: SchemaRef): SchemaRef =
      in.decodeError("reading Frictionless descriptors is not supported")

    def nullValue: SchemaRef = null
  }
