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

@JSExportTopLevel("LinkML")
@JSExportAll
object LinkMlJsApi {
  private case class JsImporter(map: js.Dictionary[String]) extends StringImporter {
    override def read(path: String): String =
      map.get(path).getOrElse(sys.error(s"Could not read from import map: $path"))
  }

  /** Generate JSON Schema from the provided LinkML model
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[importMap]].
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
    * @param open
    *   Whether the JSON Schema should allow `additionalProperties` or not.
    * @param treeRootOverride
    *   Override for the LinkML `tree_root` class which will be at the root of the JSON Schema.
    * @return
    *   Serialized JSON Schema
    */
  def jsonSchema(
      mainSchema: String,
      importMap: js.Dictionary[String],
      open: Boolean = false,
      treeRootOverride: js.UndefOr[String] = js.undefined,
  ): String = {
    val sv = SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap))
    JsonSchemaGenerator(using sv).serialize(open, treeRootOverride.toOption)
  }

  /** Generate SHACL shapes (in N-Triples format) from the provided LinkML model
    *
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[importMap]].
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
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
      mainSchema: String,
      importMap: js.Dictionary[String],
      open: Boolean = false,
      onlyClassesFromRootSchema: Boolean = false,
  ): String = {
    val sv = SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap))
    val sink = new StringSink
    ShaclGenerator(using sv).generate(NTriplesRdfSink(sink), open, onlyClassesFromRootSchema)
    sink.result
  }

  /** Generate Scala code from the provided LinkML model. This is primarily used for the metamodel
    *
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[importMap]].
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
    * @param `package`
    *   Package to generate the classes in
    * @return
    *   JS dictionary (object) containing a mapping from filename to the generated Scala code.
    */
  def scala(
      mainSchema: String,
      importMap: js.Dictionary[String],
      `package`: String,
  ): js.Dictionary[String] = {
    val sv = SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap))
    ScalaGenerator(using sv).generate(`package`).toMap.toJSDictionary
  }

  /** Generate RDFS from the provided LinkML model.
    *
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[importMap]].
    *
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    * @return
    *   JS dictionary (object) containing a mapping from filename to the generated Scala code.
    */
  def rdfs(
      mainSchema: String,
      importMap: js.Dictionary[String],
      onlyClassesFromRootSchema: Boolean,
  ): String = {
    val sv = SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap))
    val sink = new StringSink
    RdfsGenerator(using sv).generate(NTriplesRdfSink(sink), onlyClassesFromRootSchema)
    sink.result
  }

  /** Materialize a derived LinkML schema from a LinkML model. Resolves imports, derives classes,
    * and prunes unreachable elements.
    *
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[importMap]].
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
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
      mainSchema: String,
      importMap: js.Dictionary[String],
      pruningMode: String = "treeRoot",
      skipDerivation: Boolean = false,
      treeRoot: Option[String] = None,
      outFormat: String = "yaml",
  ): String = {
    val sv = SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap))
    val mode = Case.camelCase(pruningMode) match {
      case "treeRoot" => PruningMode.treeRoot(treeRoot)
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
    LinkMlGenerator(using sv).serialize(
      skipClassDerivation = skipDerivation,
      pruningMode = mode,
      outputFormat = format,
    )
  }

  /** Generate a Frictionless Table Schema from a LinkML model.
    *
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[importMap]].
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
    * @param treeRoot
    *   Tree root class name to use instead of the schema defined tree_root.
    * @return
    *   Table Schema, serialized as a JSON
    */
  def tableSchema(
      mainSchema: String,
      importMap: js.Dictionary[String],
      treeRoot: Option[String],
  ): String = {
    val sv = SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap))
    TableSchemaGenerator(using sv).serialize(treeRoot)
  }

  /** Lint the provided LinkML model, finding problems that may cause issues when using the model.
    *
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[importMap]].
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
    * @param maxProblems
    *   Maximum number of problems to include in the summary
    * @param verbose
    *   Whether to use the more verbose problem descriptions
    * @return
    *   The summary of detected problems, or an empty string if everything is correct
    */
  def lint(
      mainSchema: String,
      importMap: js.Dictionary[String],
      maxProblems: Int = 5,
      verbose: Boolean = false,
  ): String = {
    val sv = SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap))
    sv.lint(maxProblems, verbose).getOrElse("")
  }
}
