package eu.neverblink.linkml.cli

import caseapp.*
import caseapp.core.Error
import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import eu.neverblink.linkml.generator.docs.{DocsGenerator, DocsSite}
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.ossie.OssieGenerator
import eu.neverblink.linkml.generator.owl.OwlGenerator
import eu.neverblink.linkml.generator.RdfGeneratorBase.RdfFormat
import eu.neverblink.linkml.generator.util.{GeneratedHeader, JsonOutputFormat}
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.{ErrorHandling, ScalaGenerator}
import eu.neverblink.linkml.generator.pydantic.PydanticGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.stats.StatsGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.translation.TranslationGenerator
import eu.neverblink.linkml.generator.typescript.{
  ErrorHandling as TsErrorHandling,
  TypeScriptGenerator,
}
import eu.neverblink.linkml.schemaview.SchemaView

import java.io.OutputStream

// Scala

/** Parses `--error-handling`, accepting camel, kebab and snake case alike. */
private given errorHandlingParser: ArgParser[ErrorHandling] =
  SimpleArgParser.from("annotation|either") { value =>
    ErrorHandling.parse(value).toRight(
      Error.MalformedValue("error handling mode", s"$value (expected one of: annotation, either)"),
    )
  }

@HelpMessage("Generate Scala classes")
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
    @HelpMessage(
      "Whether to skip the separate interface for a concrete class that no other class extends " +
        "(with is_a or mixins), and generate it as a single case class named after it instead " +
        "of an interface plus a '...Impl' case class. Turn it off if your own code has to extend " +
        "the generated classes. Default value: true",
    )
    skipLeafInterfaces: Boolean = true,
    @HelpMessage(
      "How the functions of the schema report their errors.\n" +
        "annotation - each error class gets an exception class, and the method is annotated " +
        "with @throws for each of them.\n" +
        "either - the method returns Either[E1 | E2, R], with the error classes on the left, " +
        "and no exception classes are generated.\n" +
        "Default: annotation.",
    )
    errorHandling: ErrorHandling = ErrorHandling.annotation,
    @HelpMessage(
      "Before writing, delete the .scala files in the --to directory that start with the " +
        "generated code header, so that classes no longer in the model do not linger. " +
        "Other files are kept. Default value: false",
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
      ScalaGenerator.Options(
        `package` = options.`package`,
        generateEmitPrefixes = options.generateEmitPrefixes,
        skipLeafInterfaces = options.skipLeafInterfaces,
        errorHandling = options.errorHandling,
      ),
    )
}

// JSON Schema

@HelpMessage("Generate a JSON Schema")
@ArgsName("<input-file>")
final case class JsonSchemaOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage(
      "Whether the generated JSON Schema should allow additionalProperties for all classes, " +
        "not only for those whose extra_slots allows them. Default: false",
    )
    open: Boolean = false,
    @HelpMessage("If provided, override the schema tree_root with this class")
    treeRootOverride: Option[String] = None,
    @HelpMessage(
      "If provided, override the tree_root class' tree_root_as extension. " +
        "One of: 'plain', 'optional', 'list', 'compact_dict', 'simple_dict'. " +
        "If no extension or override is provided, the default behavior is 'plain'." +
        "See the documentation for more information.",
    )
    treeRootInlineTypeOverride: Option[String] = None,
    @HelpMessage("Allow null values for optional slots. Default: false")
    includeNull: Boolean = false,
) extends HasGenerateOptions

object JsonSchema extends StreamGenerate[JsonSchemaOptions] {
  override protected def generatorName: String = "json-schema"

  override protected[cli] def generate(options: JsonSchemaOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    JsonSchemaGenerator().writeTo(
      out,
      JsonSchemaGenerator.Options(
        options.open,
        options.treeRootOverride,
        options.treeRootInlineTypeOverride,
        // indentationStep and metadataLanguage are currently not passed to the generator
        includeNull = options.includeNull,
      ),
    )
}

// SHACL

@HelpMessage("Generate SHACL shapes")
@ArgsName("<input-file>")
final case class ShaclOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage(
      "Whether the generated SHACL should allow additional properties for all classes, " +
        "not only for those whose extra_slots allows them. Default: false",
    )
    open: Boolean = false,
    @HelpMessage(
      "Whether to include only classes from the root schema. " +
        "This is useful if you intend to generate SHACL shapes for each schema file separately, " +
        "and you don't need the imported classes to be included in the generated SHACL shapes. Default: false",
    )
    onlyClassesFromRootSchema: Boolean = false,
    @HelpMessage(RdfOutput.formatHelp)
    format: String = RdfOutput.defaultFormat,
) extends HasGenerateOptions

object Shacl extends StreamGenerate[ShaclOptions] {
  override protected def generatorName: String = "shacl"

  override protected[cli] def generate(options: ShaclOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    ShaclGenerator().writeTo(
      out,
      ShaclGenerator.Options(
        open = options.open,
        onlyClassesFromRootSchema = options.onlyClassesFromRootSchema,
        format =
          RdfOutput.parse(options.format).getOrElse(err(RdfOutput.unknownFormat(options.format))),
      ),
    )
}

// RDFS

@HelpMessage("Generate an RDF Schema (RDFS) vocabulary")
@ArgsName("<input-file>")
final case class RdfsOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage(
      "Whether to include only classes from the root schema. " +
        "This is useful if you intend to generate RDFS for each schema file separately, " +
        "and you don't need the imported classes to be included in the RDFS. Default: false",
    )
    onlyClassesFromRootSchema: Boolean = false,
    @HelpMessage(RdfOutput.formatHelp)
    format: String = RdfOutput.defaultFormat,
) extends HasGenerateOptions

object Rdfs extends StreamGenerate[RdfsOptions] {
  override protected def generatorName: String = "rdfs"

  override protected[cli] def generate(options: RdfsOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    RdfsGenerator().writeTo(
      out,
      RdfsGenerator.Options(
        onlyClassesFromRootSchema = options.onlyClassesFromRootSchema,
        format =
          RdfOutput.parse(options.format).getOrElse(err(RdfOutput.unknownFormat(options.format))),
      ),
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
    @HelpMessage(
      "Whether to describe only the root schema, with an owl:imports for each schema it imports. " +
        "By default the imported schemas are merged in. Default: false",
    )
    onlyRootSchema: Boolean = false,
    @HelpMessage(
      "Which annotation property holds descriptions: 'rdfs' (rdfs:comment, the default) or " +
        "'linkml' (skos:definition, as in the LinkML metamodel). Default: rdfs",
    )
    metadataProfile: String = "rdfs",
    @HelpMessage(
      "What permissible values become: 'individual' (named individuals, the default) or " +
        "'class' (subclasses of the enum). Default: individual",
    )
    permissibleValues: String = "individual",
    @HelpMessage(RdfOutput.formatHelp)
    format: String = RdfOutput.defaultFormat,
) extends HasGenerateOptions

object Owl extends StreamGenerate[OwlOptions] {
  override protected def generatorName: String = "owl"

  override protected[cli] def generate(options: OwlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    OwlGenerator().writeTo(
      out,
      OwlGenerator.Options(
        onlyRootSchema = options.onlyRootSchema,
        metadataProfile = OwlGenerator.MetadataProfile.values
          .find(_.toString == options.metadataProfile)
          .getOrElse(
            err(s"Unknown metadata profile '${options.metadataProfile}'. Use linkml or rdfs."),
          ),
        permissibleValues = OwlGenerator.PermissibleValueKind.values
          .find(_.toString == options.permissibleValues)
          .getOrElse(
            err(
              s"Unknown kind of permissible value '${options.permissibleValues}'. Use individual or class.",
            ),
          ),
        format =
          RdfOutput.parse(options.format).getOrElse(err(RdfOutput.unknownFormat(options.format))),
      ),
    )
}

/** The `--format` flag the SHACL, RDFS and OWL generate commands share. */
private[cli] object RdfOutput {
  val defaultFormat: String = "ttl"

  val formatHelp: String =
    "RDF serialization format: 'ttl' (Turtle – prefixed and pretty-printed, the default) " +
      "or 'nt' (N-Triples – one statement per line). Default: ttl"

  /** The format named on the command line, or None if it is not one this tool writes. */
  def parse(format: String): Option[RdfFormat] = format.toLowerCase match {
    case "nt" | "ntriples" => Some(RdfFormat.nt)
    case "ttl" | "turtle" => Some(RdfFormat.ttl)
    case _ => None
  }

  def unknownFormat(format: String): String =
    s"Unknown RDF format '$format'. Supported formats: nt, ttl."
}

/** The `--format` flag the LinkML and Ossie generate commands share. The spellings themselves live
  * on `OutputFormat`, since the JS facade and the C options take the same ones.
  */
private val outputFormatHelp: String =
  "Serialization format: 'yaml' (the default) or 'json'. Default: yaml"

/** The `--full-uris` flag of the commands that write a LinkML schema. */
private val fullUrisHelp: String =
  "Write URIs in full, instead of compacting them to CURIEs with the schema's prefixes. " +
    "Default: false"

// LinkML -> LinkML

@HelpMessage("Generate the materialized, derived LinkML schema")
@ArgsName("<input-file>")
final case class LinkMlOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage("Whether to skip the class derivation. Default: false.")
    skipDerivation: Boolean = false,
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(outputFormatHelp)
    format: String = "yaml",
    @HelpMessage(fullUrisHelp)
    fullUris: Boolean = false,
) extends HasGenerateOptions

object LinkMl extends StreamGenerate[LinkMlOptions] {
  override protected def generatorName: String = "linkml"

  override protected[cli] def generate(options: LinkMlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    LinkMlGenerator().writeTo(
      out,
      LinkMlGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        skipClassDerivation = options.skipDerivation,
        outputFormat = JsonOutputFormat.parse(options.format)
          .getOrElse(err(JsonOutputFormat.unknownFormat(options.format))),
        fullUris = options.fullUris,
      ),
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
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(
      "Whether to skip classes that have no identifier slot. Such a table gets no primary key and " +
        "nothing can reference it, so it is often not useful. Default: false",
    )
    skipClassesWithoutIdentifier: Boolean = false,
) extends HasGenerateOptions

object Frictionless extends SplitGenerate[FrictionlessOptions] {
  override protected def generatorName: String = "frictionless"

  override protected def singleFileExtension: String = ".json"

  private def generator(options: FrictionlessOptions): FrictionlessGenerator.Options =
    FrictionlessGenerator.Options(
      pruningMode = options.pruning.resolvedPruningMode,
      skipClassesWithoutIdentifier = options.skipClassesWithoutIdentifier,
    )

  override protected[cli] def generateSingle(options: FrictionlessOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    FrictionlessGenerator().writeTo(out, generator(options))

  override protected[cli] def generateFiles(options: FrictionlessOptions)(using
      SchemaView,
  ): Iterable[(String, String)] =
    FrictionlessGenerator().generateFiles(generator(options))
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
    pruning: PruningOptions = PruningOptions(),
) extends HasGenerateOptions

object GraphQl extends StreamGenerate[GraphQlOptions] {
  override protected def generatorName: String = "graphql"

  override protected[cli] def generate(options: GraphQlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    GraphQlGenerator().writeTo(
      out,
      GraphQlGenerator.Options(options.pruning.resolvedPruningMode),
    )
}

// TypeScript

/** Parses `--error-handling` for TypeScript, accepting camel, kebab and snake case alike. */
private given tsErrorHandlingParser: ArgParser[TsErrorHandling] =
  SimpleArgParser.from("throw|result") { value =>
    TsErrorHandling.parse(value).toRight(
      Error.MalformedValue("error handling mode", s"$value (expected one of: throw, result)"),
    )
  }

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
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(
      "Whether all generated interfaces should allow additional properties, " +
        "not only those of classes whose extra_slots allows them. Default: false",
    )
    open: Boolean = false,
    @HelpMessage("Allow null values for optional slots. Default: false")
    includeNull: Boolean = false,
    @HelpMessage(
      "How the functions of the schema report their errors.\n" +
        "throw - the method returns the value, and its doc comment gets a @throws tag for each " +
        "error class.\n" +
        "result - the method returns Result<R, Failure<...> | ...>: the value or one of the " +
        "errors.\n" +
        "Default: throw.",
    )
    errorHandling: TsErrorHandling = TsErrorHandling.exception,
) extends HasGenerateOptions

object TypeScript extends StreamGenerate[TypeScriptOptions] {
  override protected def generatorName: String = "typescript"

  override protected[cli] def generate(options: TypeScriptOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    TypeScriptGenerator().writeTo(
      out,
      TypeScriptGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        includeNull = options.includeNull,
        open = options.open,
        errorHandling = options.errorHandling,
      ),
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
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(
      "Whether the generated classes should accept and keep additional properties. Default: false",
    )
    open: Boolean = false,
    @HelpMessage(
      "Keep the nulls that were loaded or set when dumping. Otherwise dumps leave out " +
        "every field without a value. Default: false",
    )
    includeNull: Boolean = false,
) extends HasGenerateOptions

object Pydantic extends StreamGenerate[PydanticOptions] {
  override protected def generatorName: String = "pydantic"

  override protected[cli] def generate(options: PydanticOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    PydanticGenerator().writeTo(
      out,
      PydanticGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        includeNull = options.includeNull,
        open = options.open,
      ),
    )
}

// Apache Ossie ontology

@HelpMessage("Generate an Apache Ossie ontology")
@ArgsName("<input-file>")
final case class OssieOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(outputFormatHelp)
    format: String = "yaml",
) extends HasGenerateOptions

object Ossie extends StreamGenerate[OssieOptions] {
  override protected def generatorName: String = "ossie"

  override protected[cli] def generate(options: OssieOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    OssieGenerator().writeTo(
      out,
      OssieGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        outputFormat = JsonOutputFormat.parse(options.format)
          .getOrElse(err(JsonOutputFormat.unknownFormat(options.format))),
      ),
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
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(
      "Whether to mark optional attributes with a trailing '?' on their type. " +
        "Mermaid understands this from version 11.16 onwards, and older renderers reject the " +
        "whole diagram rather than just the marker, so pass --optional-marker=false when the " +
        "diagram is headed somewhere that pins an older Mermaid. Default value: true",
    )
    optionalMarker: Boolean = true,
) extends HasGenerateOptions

object ErDiagram extends StreamGenerate[ErDiagramOptions] {
  override protected def generatorName: String = "er-diagram"

  override protected[cli] def generate(options: ErDiagramOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    ErDiagramGenerator().writeTo(
      out,
      ErDiagramGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        optionalMarker = options.optionalMarker,
      ),
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
    @HelpMessage(
      s"Framework name to generate a translation dict for. One of: ${TranslationGenerator.targets.mkString(", ")}",
    )
    target: String = "base",
    @HelpMessage(
      "Also list inherited slots, mixin slots and slots from the class's slots list in " +
        "classAttributes. Without it, classAttributes lists only the class's own attributes. " +
        "Default: false",
    )
    derivedAttributes: Boolean = false,
) extends HasGenerateOptions

object Translation extends StreamGenerate[TranslationOptions] {
  override protected def generatorName: String = "translation"

  override protected[cli] def generate(options: TranslationOptions, out: OutputStream)(using
      sv: SchemaView,
  ): Unit = {
    TranslationGenerator(using sv).writeTo(
      out,
      TranslationGenerator.Options(options.target, derivedAttributes = options.derivedAttributes),
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
    pruning: DocsPruningOptions = DocsPruningOptions(),
    @HelpMessage("Which language to use for titles and descriptions. Default: en")
    metadataLanguage: String = "en",
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

  private def generator(options: DocsOptions): DocsGenerator.Options =
    DocsGenerator.Options(
      pruningMode = options.pruning.resolvedPruningMode,
      metadataLanguage = options.metadataLanguage,
    )

  override protected[cli] def generateSingle(options: DocsOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    DocsGenerator().writeTo(out, generator(options))

  override protected[cli] def generateFiles(options: DocsOptions)(using
      SchemaView,
  ): Iterable[(String, String)] =
    DocsGenerator().generateFiles(generator(options)).map(_.toTuple)
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
    pruning: DocsPruningOptions = DocsPruningOptions(),
    @HelpMessage(outputFormatHelp)
    format: String = "yaml",
) extends HasGenerateOptions

object Stats extends StreamGenerate[StatsOptions] {
  override protected def generatorName: String = "stats"

  override protected[cli] def generate(options: StatsOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    StatsGenerator().writeTo(
      out,
      StatsGenerator.Options(pruningMode = options.pruning.resolvedPruningMode),
      JsonOutputFormat.parse(options.format)
        .getOrElse(err(JsonOutputFormat.unknownFormat(options.format))),
    )
}
