package eu.neverblink.linkml.js

import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import LinkMlGenerator.PruningMode
import eu.neverblink.linkml.generator.rdf.NTriplesRdfSink
import eu.neverblink.linkml.generator.util.StringSink
import eu.neverblink.linkml.generator.tableschema.TableSchemaGenerator
import eu.neverblink.linkml.schemaview.{StringImporter, SchemaView, Case}

import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichMap
import scala.scalajs.js.annotation.{JSExportAll, JSExportTopLevel}

/** Handle to a loaded, import-resolved LinkML schema.
  *
  * Wraps a Scala [[SchemaView]] so the schema is parsed once (with all `imports:` resolved) and can
  * then be reused across many generators, instead of re-parsing on every call. Create one with
  * [[LinkMlJsApi.loadFromString]] or [[LinkMlJsApi.loadFromPath]].
  */
final class SchemaViewJs private[js] (private[js] val underlying: SchemaView)

@JSExportTopLevel("LinkML")
@JSExportAll
object LinkMlJsApi {
  private case class JsImporter(map: js.Dictionary[String]) extends StringImporter {
    override def read(path: String): String =
      map.get(path).getOrElse(sys.error(s"Could not read from import map: $path"))
  }

  /** Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from the
    * schema's YAML text.
    *
    * The main schema is parsed directly from `mainSchema`, so it has no path of its own. If one of
    * its imports (transitively) imports the main schema back by filename, that import cannot be
    * matched against the root and the main schema will be loaded a second time. Use
    * [[loadFromPath]] instead when the root schema takes part in an import cycle.
    *
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[importMap]].
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
    * @return
    *   An opaque [[SchemaView]] handle to pass to the generator functions.
    */
  def loadFromString(mainSchema: String, importMap: js.Dictionary[String]): SchemaViewJs =
    new SchemaViewJs(SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap)))

  /** Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from a path
    * into the [[importMap]].
    *
    * Unlike [[loadFromString]], the main schema is read through the import map by its own path, so
    * it is tracked from the start of import resolution. This makes it immune to cyclic imports
    * involving the root schema: an import that (transitively) references the root back by path
    * resolves to the already-loaded root instead of loading it again.
    *
    * Paths behave like file paths: a `.yaml` extension is appended when missing, and relative
    * imports are resolved against the directory of their importing schema. The [[importMap]] keys
    * must therefore be the paths as seen from the root (e.g. `"model.yaml"`,
    * `"nested/person.yaml"`).
    *
    * @param path
    *   Path of the main LinkML model within the [[importMap]] (e.g. `"model.yaml"`).
    * @param importMap
    *   JS dictionary (object) containing a mapping from path to LinkML models (in YAML format),
    *   including the main schema itself under [[path]].
    * @return
    *   An opaque [[SchemaView]] handle to pass to the generator functions.
    */
  def loadFromPath(path: String, importMap: js.Dictionary[String]): SchemaViewJs =
    new SchemaViewJs(SchemaView.loadSchemaViewFromUri(path, JsImporter(importMap)))

  /** Generate JSON Schema from a loaded LinkML schema.
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param open
    *   Whether the JSON Schema should allow `additionalProperties` or not.
    * @param treeRootOverride
    *   Override for the LinkML `tree_root` class which will be at the root of the JSON Schema.
    * @return
    *   Serialized JSON Schema
    */
  def jsonSchema(
      schema: SchemaViewJs,
      open: Boolean = false,
      treeRootOverride: js.UndefOr[String] = js.undefined,
  ): String =
    JsonSchemaGenerator(using schema.underlying).serialize(open, treeRootOverride.toOption)

  /** Generate SHACL shapes (in N-Triples format) from a loaded LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param open
    *   Whether the SHACL shapes should be open (`_:b sh:closed false .`, allowing additional
    *   properties).
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    * @return
    *   SHACL shapes in N-Triples format
    */
  def shacl(
      schema: SchemaViewJs,
      open: Boolean = false,
      onlyClassesFromRootSchema: Boolean = false,
  ): String = {
    val sink = new StringSink
    ShaclGenerator(using schema.underlying).generate(
      NTriplesRdfSink(sink),
      open,
      onlyClassesFromRootSchema,
    )
    sink.result
  }

  /** Generate Scala code from a loaded LinkML schema. This is primarily used for the metamodel
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param `package`
    *   Package to generate the classes in
    * @return
    *   JS dictionary (object) containing a mapping from filename to the generated Scala code.
    */
  def scala(
      schema: SchemaViewJs,
      `package`: String,
  ): js.Dictionary[String] =
    ScalaGenerator(using schema.underlying).generate(`package`).toMap.toJSDictionary

  /** Generate RDFS from a loaded LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    * @return
    *   RDFS in N-Triples format
    */
  def rdfs(
      schema: SchemaViewJs,
      onlyClassesFromRootSchema: Boolean = false,
  ): String = {
    val sink = new StringSink
    RdfsGenerator(using schema.underlying).generate(
      NTriplesRdfSink(sink),
      onlyClassesFromRootSchema,
    )
    sink.result
  }

  /** Materialize a derived LinkML schema from a loaded LinkML schema. Derives classes and prunes
    * unreachable elements.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param pruningMode
    *   Pruning mode to use for removing unused elements (classes, types, enums). One of
    *   treeRoot|schemaRoot|skip. treeRoot - remove all elements unreachable from the tree_root
    *   class. schema - remove all elements unreachable from any of the classes defined in the root
    *   schema. skip - do not remove unused elements. Default: treeRoot
    * @param skipDerivation
    *   If true, will not derive classes and instead copy them as-is.
    * @param treeRoot
    *   Tree root class name to use instead of the schema defined tree_root. Does nothing if not in
    *   tree root pruning mode.
    * @param outFormat
    *   Output serialization format to use. One of yaml|json. Default: yaml
    * @return
    *   The derived [[SchemaDefinition]] serialized in the specified format.
    */
  def linkml(
      schema: SchemaViewJs,
      pruningMode: String = "treeRoot",
      skipDerivation: Boolean = false,
      treeRoot: js.UndefOr[String] = js.undefined,
      outFormat: String = "yaml",
  ): String = {
    val mode = Case.camelCase(pruningMode) match {
      case "treeRoot" => PruningMode.treeRoot(treeRoot.toOption)
      case "schema" => PruningMode.schemaRoot
      case "skip" => PruningMode.skip
      case s => throw RuntimeException(s"Unknown pruning mode: $s")
    }

    val format = outFormat.toLowerCase match {
      case "yaml" => LinkMlGenerator.OutputFormat.yaml
      case "yml" => LinkMlGenerator.OutputFormat.yaml
      case "json" => LinkMlGenerator.OutputFormat.json
      case s => throw RuntimeException(s"Unknown output format: $s")
    }
    LinkMlGenerator(using schema.underlying).serialize(
      skipClassDerivation = skipDerivation,
      pruningMode = mode,
      outputFormat = format,
    )
  }

  /** Generate a Frictionless Table Schema from a loaded LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param treeRoot
    *   Tree root class name to use instead of the schema defined tree_root.
    * @return
    *   Table Schema, serialized as a JSON
    */
  def tableSchema(
      schema: SchemaViewJs,
      treeRoot: js.UndefOr[String] = js.undefined,
  ): String =
    TableSchemaGenerator(using schema.underlying).serialize(treeRoot.toOption)

  /** Lint a loaded LinkML schema, finding problems that may cause issues when using the model.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param maxProblems
    *   Maximum number of problems to include in the summary
    * @param verbose
    *   Whether to use the more verbose problem descriptions
    * @return
    *   The summary of detected problems, or an empty string if everything is correct
    */
  def lint(
      schema: SchemaViewJs,
      maxProblems: Int = 5,
      verbose: Boolean = false,
  ): String =
    schema.underlying.lint(maxProblems, verbose).getOrElse("")
}
