package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.rdf.RdfUtils
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.schemaview.SchemaView

// Scala

@HelpMessage("Generate Scala classes from a LinkML model")
@ArgsName("<input-file>")
final case class ScalaOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage(
      "Package name for generated Scala classes. Default value: eu.neverblink.linkml.metamodel",
    )
    `package`: String = "eu.neverblink.linkml.metamodel",
    @HelpMessage(
      "Whether to generate a 'Prefixes' object with the model's emit_prefixes inside. Default value: true",
    )
    generateEmitPrefixes: Boolean = true,
) extends HasGenerateOptions

object Scala extends Generate[ScalaOptions] {
  override protected def generatorName: String = "scala"

  override protected def generate(
      options: ScalaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    ScalaGenerator().generate(options.`package`, options.generateEmitPrefixes)
}

// JSON Schema

@HelpMessage("Generate JSON Schema from a LinkML model")
@ArgsName("<input-file>")
final case class JsonSchemaOptions(
    @Recurse
    common: GenerateOptions,
) extends HasGenerateOptions

object JsonSchema extends Generate[JsonSchemaOptions] {
  override protected def generatorName: String = "json-schema"

  override protected def generate(
      options: JsonSchemaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    Seq(
      ("", JsonSchemaGenerator().serialize()),
    )
}

// SHACL

@HelpMessage("Generate SHACL shapes from a LinkML model")
@ArgsName("<input-file>")
final case class ShaclOptions(
    @Recurse
    common: GenerateOptions,
) extends HasGenerateOptions

object Shacl extends Generate[ShaclOptions] {
  override protected def generatorName: String = "shacl"

  override protected def generate(
      options: ShaclOptions,
  )(using SchemaView): Iterable[(String, String)] =
    Seq(
      ("", RdfUtils.toTurtle(ShaclGenerator().generate())),
    )
}

// RDFS

@HelpMessage("Generate RDF schema from a LinkML model")
@ArgsName("<input-file>")
final case class RdfsOptions(
    @Recurse
    common: GenerateOptions,
) extends HasGenerateOptions

object Rdfs extends Generate[RdfsOptions] {
  override protected def generatorName: String = "rdfs"

  override protected def generate(
      options: RdfsOptions,
  )(using SchemaView): Iterable[(String, String)] =
    Seq(
      ("", RdfUtils.toTurtle(RdfsGenerator().generate())),
    )
}
