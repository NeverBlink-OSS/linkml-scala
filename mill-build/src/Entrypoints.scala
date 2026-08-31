package millbuild

/** The generators exposed by the native library exposes.
  *
  * This is used by the C and Python code generators.
  */
object Entrypoints {

  /** @param python
    *   Python method name on `linkml_scala.Schema`
    * @param symbol
    *   the exported C function
    * @param scalaMethod
    *   the `LinkMlNativeApi` method the C entry point calls
    * @param generator
    *   the generator object holding the `Options` case class
    * @param returns
    *   Python return annotation. Anything but `str` is parsed as JSON.
    * @param summary
    *   first line of the Python docstring
    * @param cComment
    *   one-line description for the C entry point
    */
  final case class Entrypoint(
      python: String,
      symbol: String,
      scalaMethod: String,
      generator: String,
      returns: String,
      summary: String,
      cComment: String,
  ) {

    /** Where the generator lives, derived from its name: the package is the lower-cased name minus
      * the `Generator` suffix, which is the layout the whole generator module follows.
      */
    def source: String = {
      val pkg = generator.stripSuffix("Generator").toLowerCase
      s"generator/src/eu/neverblink/linkml/generator/$pkg/$generator.scala"
    }

    /** Whether the result is JSON to be parsed rather than a document to hand back as-is. */
    def structured: Boolean = returns != "str"
  }

  val all: Seq[Entrypoint] = Seq(
    Entrypoint(
      "json_schema",
      "linkml_json_schema",
      "jsonSchema",
      "JsonSchemaGenerator",
      "str",
      "Generate a JSON Schema.",
      "Generate JSON Schema.",
    ),
    Entrypoint(
      "shacl",
      "linkml_shacl",
      "shacl",
      "ShaclGenerator",
      "str",
      "Generate SHACL shapes, serialized as N-Triples or Turtle.",
      "Generate SHACL shapes as RDF.",
    ),
    Entrypoint(
      "rdfs",
      "linkml_rdfs",
      "rdfs",
      "RdfsGenerator",
      "str",
      "Generate RDFS, serialized as N-Triples or Turtle.",
      "Generate RDFS as RDF.",
    ),
    Entrypoint(
      "linkml",
      "linkml_linkml",
      "linkml",
      "LinkMlGenerator",
      "str",
      "Materialize a derived LinkML schema: imports resolved, slots pushed into attributes.",
      "Materialize a derived LinkML schema.",
    ),
    Entrypoint(
      "frictionless",
      "linkml_frictionless",
      "frictionlessFiles",
      "FrictionlessGenerator",
      "dict[str, str]",
      "Generate a Frictionless Data Package, as a filename to content mapping.",
      "Generate a Frictionless Data Package, as a JSON object mapping filename to content.",
    ),
    Entrypoint(
      "graphql",
      "linkml_graphql",
      "graphQl",
      "GraphQlGenerator",
      "str",
      "Generate a GraphQL schema: types, interfaces, scalars and enums, but no queries.",
      "Generate a GraphQL schema.",
    ),
    Entrypoint(
      "er_diagram",
      "linkml_er_diagram",
      "erDiagram",
      "ErDiagramGenerator",
      "str",
      "Generate a Mermaid entity relationship diagram.",
      "Generate a Mermaid entity relationship diagram.",
    ),
    Entrypoint(
      "scala",
      "linkml_scala",
      "scalaFiles",
      "ScalaGenerator",
      "dict[str, str]",
      "Generate Scala classes, as a filename to source mapping.",
      "Generate Scala sources, as a JSON object mapping filename to source.",
    ),
  )
}
