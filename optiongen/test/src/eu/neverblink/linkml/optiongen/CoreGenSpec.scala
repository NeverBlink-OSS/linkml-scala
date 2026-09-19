package eu.neverblink.linkml.optiongen

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CoreGenSpec extends AnyWordSpec, Matchers {
  "CoreGen" should {
    "render source-compatible types and defaults while escaping schema text" in {
      val fields = Vector(
        ("package", ValueType.Text, Value.Text("\"quoted\"\\path\n\t")),
        ("open", ValueType.Bool, Value.Bool(false)),
        ("indentationStep", ValueType.Int32, Value.Int32(0)),
        ("treeRoot", ValueType.OptionalText, Value.Text("Person")),
        ("treeRootInlineType", ValueType.OptionalText, Value.Absent),
        ("format", ValueType.RdfFormat, Value.Enum("nt")),
        ("outputFormat", ValueType.JsonFormat, Value.Enum("json")),
        ("pruningMode", ValueType.Pruning, Value.Pruning(PruningKind.TreeRoot, Some("Root"))),
      ).zipWithIndex.map { case ((name, valueType, default), index) =>
        FieldDef(
          name,
          index + 1,
          valueType,
          default,
          if index == 0 then "Package */ description.\nSecond line." else "",
          "fixture.yaml",
        )
      }
      val generator = GeneratorDef(
        "shacl",
        "ShaclOptions",
        "Schema */ and /* delimiters.\nSecond line.",
        fields,
        Map.empty,
        Some(ScalaParent.RdfOptions),
      )
      val entry = Entrypoints.all.find(_.python == "shacl").get
      val result = CoreGen.render(entry, generator)
      result should include("/** Schema * / and / * delimiters.\n  * Second line.")
      result should include("@param package\n  *   Package * / description.\n  *   Second line.")
      result should include("final case class Options(")
      val declarations = Vector(
        """`package`: String = "\"quoted\"\\path\n\t",""",
        "open: Boolean = false,",
        "indentationStep: Int = 0,",
        """treeRoot: Option[String] = Some("Person"),""",
        "treeRootInlineType: Option[String] = None,",
        "format: _root_.eu.neverblink.linkml.generator.rdf.RdfFormat = _root_.eu.neverblink.linkml.generator.rdf.RdfFormat.nt,",
        "outputFormat: _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat = _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat.json,",
        """pruningMode: _root_.eu.neverblink.linkml.generator.util.PruningMode = _root_.eu.neverblink.linkml.generator.util.PruningMode.treeRoot(Some("Root")),""",
      )
      declarations.foreach(result should include(_))
      result should endWith(") extends _root_.eu.neverblink.linkml.generator.rdf.RdfOptions\n")

      val catalog = OptionSchemaReader.load(sys.env("OPTION_SCHEMA")).toOption.get
      val output = Entrypoints.all.map { entry =>
        entry.python -> CoreGen.render(entry, catalog.generators.find(_.id == entry.python).get)
      }.toMap
      output("graphql") should include(
        "pruningMode: _root_.eu.neverblink.linkml.generator.util.PruningMode = _root_.eu.neverblink.linkml.generator.util.PruningMode.schemaRoot,",
      )
      output("er_diagram") should include(
        "pruningMode: _root_.eu.neverblink.linkml.generator.util.PruningMode = _root_.eu.neverblink.linkml.generator.util.PruningMode.schemaRoot,",
      )
      output("linkml") should include(
        "outputFormat: _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat = _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat.yaml,",
      )
      output("ossie") should include(
        "outputFormat: _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat = _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat.yaml,",
      )
      output("linkml") should include(
        "pruningMode: _root_.eu.neverblink.linkml.generator.util.PruningMode = _root_.eu.neverblink.linkml.generator.util.PruningMode.skip,",
      )
      output("shacl") should include(
        "format: _root_.eu.neverblink.linkml.generator.rdf.RdfFormat = _root_.eu.neverblink.linkml.generator.rdf.RdfFormat.ttl,",
      )
    }
  }
}
