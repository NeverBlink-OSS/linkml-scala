package eu.neverblink.linkml.generator

import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue

/** Models with JSON documents loaded in the code generators' integration tests: all models from the
  * catalogue, plus one model for every `tree_root_as` form.
  */
object DocumentModels {

  /** A model to check, with its valid and invalid JSON documents by name. */
  final case class Model(
      name: String,
      sv: SchemaView,
      valid: Seq[(String, String)],
      invalid: Seq[(String, String)],
  )

  val catalogue: Seq[Model] = ModelCatalogue.all.map { entry =>
    def jsons(instances: Seq[ModelCatalogue.InstanceInFormats]) =
      instances.distinct.flatMap(i => i.json.map(i.name -> _))
    Model(entry.name, entry.model, jsons(entry.validInstances), jsons(entry.invalidInstances))
  }

  /** No catalogue model has a tree root in another form than `plain`, so these cover the rest. */
  val documentForms: Seq[Model] = {
    def model(name: String, form: String, attributes: String) = SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(
        s"""id: https://neverblink.eu/linkml/documents/$name/
           |name: $name
           |imports:
           |  - linkml:types
           |classes:
           |  Item:
           |    tree_root: true
           |    extensions:
           |      tree_root_as: $form
           |    attributes:
           |$attributes""".stripMargin,
      ),
    )
    val plainAttributes =
      """      name:
        |        required: true
        |      count:
        |        range: integer
        |""".stripMargin
    Seq(
      Model(
        "optionalDocument",
        model("optionalDocument", "optional", plainAttributes),
        Seq("null" -> "null", "object" -> """{"name": "a"}"""),
        Seq("list" -> """[{"name": "a"}]"""),
      ),
      Model(
        "listDocument",
        model("listDocument", "list", plainAttributes),
        Seq("empty" -> "[]", "two" -> """[{"name": "a"}, {"name": "b", "count": 2}]"""),
        Seq("object" -> """{"name": "a"}""", "wrongType" -> """[{"name": "a", "count": "2"}]"""),
      ),
      Model(
        "compactDictDocument",
        model(
          "compactDictDocument",
          "compact_dict",
          """      id:
            |        identifier: true
            |      name:
            |      count:
            |        range: integer
            |""".stripMargin,
        ),
        Seq(
          "entries" -> """{"a": {"name": "A", "count": 1}, "b": {"id": "b"}, "c": {}}""",
        ),
        Seq(
          "number" -> """{"a": 1}""",
          "unknownKey" -> """{"a": {"colour": "red"}}""",
          "wrongType" -> """{"a": {"count": "1"}}""",
        ),
      ),
      Model(
        "simpleDictDocument",
        model(
          "simpleDictDocument",
          "simple_dict",
          """      key:
            |        key: true
            |      label:
            |""".stripMargin,
        ),
        Seq(
          "entries" -> """{"a": "A", "b": {"label": "B"}, "c": {"key": "c", "label": "C"}}""",
        ),
        Seq("number" -> """{"a": 1}""", "unknownKey" -> """{"a": {"colour": "red"}}"""),
      ),
    )
  }

  /** A file or directory name made of the model or document name. */
  def safeName(name: String): String = name.map(c => if c.isLetterOrDigit then c else '_')
}
