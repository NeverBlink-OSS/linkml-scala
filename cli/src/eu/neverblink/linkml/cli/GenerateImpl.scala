package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.cli.options.*
import eu.neverblink.linkml.cli.options.EnumParsers.given
import eu.neverblink.linkml.generator.docs.{DocsGenerator, DocsSite}
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.ossie.OssieGenerator
import eu.neverblink.linkml.generator.owl.OwlGenerator
import eu.neverblink.linkml.generator.options.JsonOutputFormat
import eu.neverblink.linkml.generator.util.GeneratedHeader
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.pydantic.PydanticGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.stats.StatsGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.translation.TranslationGenerator
import eu.neverblink.linkml.generator.typescript.TypeScriptGenerator
import eu.neverblink.linkml.schemaview.SchemaView

import java.io.OutputStream

// Scala

@HelpMessage("Generate Scala classes")
@ArgsName("<input-file>")
final case class ScalaOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: ScalaCliOptions = ScalaCliOptions(),
    @HelpMessage(
      "Before writing, delete the .scala files in the --to directory that start with the " +
        "generated code header, so that classes no longer in the model do not linger. " +
        "Other files are kept. Default: false",
    )
    clean: Boolean = false,
) extends HasGenerateOptions

object Scala extends ManyFilesGenerate[ScalaOptions] {
  override protected def generatorName: String = "scala"

  override protected[cli] def staleFile(options: ScalaOptions): Option[os.Path => Boolean] =
    Option.when(options.clean) { file =>
      file.ext == "scala" &&
      os.read.lines.stream(file).headOption.exists(GeneratedHeader.startsHeader(_, "//"))
    }

  override protected[cli] def generate(
      options: ScalaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    ScalaGenerator().generate(
      options.generator.toGeneratorOptions,
    )
}

// JSON Schema

@HelpMessage("Generate a JSON Schema")
@ArgsName("<input-file>")
final case class JsonSchemaOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: JsonSchemaCliOptions = JsonSchemaCliOptions(),
) extends HasGenerateOptions

object JsonSchema extends StreamGenerate[JsonSchemaOptions] {
  override protected def generatorName: String = "json-schema"

  override protected[cli] def generate(options: JsonSchemaOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    JsonSchemaGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// SHACL

@HelpMessage("Generate SHACL shapes")
@ArgsName("<input-file>")
final case class ShaclOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: ShaclCliOptions = ShaclCliOptions(),
) extends HasGenerateOptions

object Shacl extends StreamGenerate[ShaclOptions] {
  override protected def generatorName: String = "shacl"

  override protected[cli] def generate(options: ShaclOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    ShaclGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// RDFS

@HelpMessage("Generate an RDF Schema (RDFS) vocabulary")
@ArgsName("<input-file>")
final case class RdfsOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: RdfsCliOptions = RdfsCliOptions(),
) extends HasGenerateOptions

object Rdfs extends StreamGenerate[RdfsOptions] {
  override protected def generatorName: String = "rdfs"

  override protected[cli] def generate(options: RdfsOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    RdfsGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// OWL

@HelpMessage(
  "Generate an OWL 2 ontology\n" +
    "Classes, slots, enums and types become OWL classes, properties, classes of individuals " +
    "and datatypes, with the same IRIs as the SHACL and RDFS generators.",
)
@ArgsName("<input-file>")
final case class OwlOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: OwlCliOptions = OwlCliOptions(),
) extends HasGenerateOptions

object Owl extends StreamGenerate[OwlOptions] {
  override protected def generatorName: String = "owl"

  override protected[cli] def generate(options: OwlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    OwlGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// LinkML -> LinkML

@HelpMessage("Generate the materialized, derived LinkML schema")
@ArgsName("<input-file>")
final case class LinkMlOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: LinkMlCliOptions = LinkMlCliOptions(),
) extends HasGenerateOptions

object LinkMl extends StreamGenerate[LinkMlOptions] {
  override protected def generatorName: String = "linkml"

  override protected[cli] def generate(options: LinkMlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    LinkMlGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// Table Schema

@HelpMessage(
  "Generate a Frictionless Data Package\n" +
    "If --to is a directory, the package is written as a datapackage.json file plus one " +
    "schemas/<table>.json per table. If --to is a .json file, or with no --to at all, it is " +
    "written as a single file with table schemas inlined.",
)
@ArgsName("<input-file>")
final case class FrictionlessOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: FrictionlessCliOptions = FrictionlessCliOptions(),
) extends HasGenerateOptions

object Frictionless extends SplitGenerate[FrictionlessOptions] {
  override protected def generatorName: String = "frictionless"

  override protected def singleFileExtension: String = ".json"

  override protected[cli] def generateSingle(options: FrictionlessOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    FrictionlessGenerator().writeTo(out, options.generator.toGeneratorOptions)

  override protected[cli] def generateFiles(options: FrictionlessOptions)(using
      SchemaView,
  ): Iterable[(String, String)] =
    FrictionlessGenerator().generateFiles(options.generator.toGeneratorOptions)
}

// GraphQL

@HelpMessage(
  "Generate a GraphQL schema\n" +
    "Only types, interfaces, scalars and enums are generated. Add queries by hand.",
)
@ArgsName("<input-file>")
final case class GraphQlOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: GraphQlCliOptions = GraphQlCliOptions(),
) extends HasGenerateOptions

object GraphQl extends StreamGenerate[GraphQlOptions] {
  override protected def generatorName: String = "graphql"

  override protected[cli] def generate(options: GraphQlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    GraphQlGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// TypeScript

@HelpMessage(
  "Generate TypeScript types\n" +
    "The types describe the same JSON as the generated JSON Schema, with no runtime code: " +
    "load data with JSON.parse and a type cast, dump it with JSON.stringify.",
)
@ArgsName("<input-file>")
final case class TypeScriptOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: TypeScriptCliOptions = TypeScriptCliOptions(),
) extends HasGenerateOptions

object TypeScript extends StreamGenerate[TypeScriptOptions] {
  override protected def generatorName: String = "typescript"

  override protected[cli] def generate(options: TypeScriptOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    TypeScriptGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// Pydantic

@HelpMessage(
  "Generate Python classes based on pydantic\n" +
    "The classes load and dump the same JSON as the generated JSON Schema describes: " +
    "load data with X.model_validate_json, dump it with x.model_dump_json.",
)
@ArgsName("<input-file>")
final case class PydanticOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: PydanticCliOptions = PydanticCliOptions(),
) extends HasGenerateOptions

object Pydantic extends StreamGenerate[PydanticOptions] {
  override protected def generatorName: String = "pydantic"

  override protected[cli] def generate(options: PydanticOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    PydanticGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// Apache Ossie ontology

@HelpMessage("Generate an Apache Ossie ontology")
@ArgsName("<input-file>")
final case class OssieOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: OssieCliOptions = OssieCliOptions(),
) extends HasGenerateOptions

object Ossie extends StreamGenerate[OssieOptions] {
  override protected def generatorName: String = "ossie"

  override protected[cli] def generate(options: OssieOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    OssieGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

// ER diagram

@HelpMessage(
  "Generate a Mermaid entity relationship diagram\n" +
    "Classes become entities, type- and enum-ranged slots become their attributes, and " +
    "class-ranged slots become relationship lines.",
)
@ArgsName("<input-file>")
final case class ErDiagramOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: ErDiagramCliOptions = ErDiagramCliOptions(),
) extends HasGenerateOptions

object ErDiagram extends StreamGenerate[ErDiagramOptions] {
  override protected def generatorName: String = "er-diagram"

  override protected[cli] def generate(options: ErDiagramOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    ErDiagramGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
}

@HelpMessage(
  "Generate translation dictionaries in JSON\n" +
    "They map the original names used in the schema to the names used in generated outputs.",
)
@ArgsName("<input-file>")
final case class TranslationOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: TranslationCliOptions = TranslationCliOptions(),
) extends HasGenerateOptions

object Translation extends StreamGenerate[TranslationOptions] {
  override protected def generatorName: String = "translation"

  override protected[cli] def generate(options: TranslationOptions, out: OutputStream)(using
      sv: SchemaView,
  ): Unit = {
    TranslationGenerator(using sv).writeTo(
      out,
      options.generator.toGeneratorOptions,
    )
  }
}

// Documentation

@HelpMessage(
  "Generate Markdown documentation\n" +
    "If --to is a directory, the documentation is written as index.md plus one page per class, " +
    "slot, enum, type and function, in the classes/, slots/, enums/, types/ and functions/ " +
    "folders. If --to is a .md file, or with no --to at all, it is written as a single document.",
)
@ArgsName("<input-file>")
final case class DocsOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: DocsCliOptions = DocsCliOptions(),
) extends HasGenerateOptions

object Docs extends SplitGenerate[DocsOptions] {
  override protected def generatorName: String = "docs"

  override protected def singleFileExtension: String = ".md"

  /** The pages in the folders of each kind of element that start with the generated header, so that
    * the pages of elements no longer in the schema do not linger. Other files are kept.
    */
  override protected[cli] def staleFiles(dir: os.Path): Iterable[os.Path] =
    DocsSite.PageKind.values.toSeq.flatMap { kind =>
      val folder = dir / kind.dir
      if (os.isDir(folder))
        os.list(folder).filter { file =>
          os.isFile(file) && file.ext == "md" &&
          os.read.lines.stream(file).headOption.exists(GeneratedHeader.startsHeader(_, "<!--"))
        }
      else Nil
    }

  override protected[cli] def generateSingle(options: DocsOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    DocsGenerator().writeTo(out, options.generator.toGeneratorOptions)

  override protected[cli] def generateFiles(options: DocsOptions)(using
      SchemaView,
  ): Iterable[(String, String)] =
    DocsGenerator().generateFiles(options.generator.toGeneratorOptions).map(_.toTuple)
}

// Schema statistics

@HelpMessage(
  "Generate statistics about the schema\n" +
    "Counts the elements of each kind, how many of them are documented, deprecated or mapped, " +
    "the strings in each language, and the imports.",
)
@ArgsName("<input-file>")
final case class StatsOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    generator: StatsCliOptions = StatsCliOptions(),
    @HelpMessage("The serialization of the output: yaml or json. Default: yaml")
    format: JsonOutputFormat = JsonOutputFormat.Yaml,
) extends HasGenerateOptions

object Stats extends StreamGenerate[StatsOptions] {
  override protected def generatorName: String = "stats"

  override protected[cli] def generate(options: StatsOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    StatsGenerator().writeTo(
      out,
      options.generator.toGeneratorOptions,
      options.format,
    )
}
