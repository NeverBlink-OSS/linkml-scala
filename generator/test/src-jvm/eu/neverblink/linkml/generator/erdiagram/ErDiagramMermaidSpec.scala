package eu.neverblink.linkml.generator.erdiagram

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks the generated ER diagrams against the real Mermaid parser, for every model in the
  * catalogue and for the LinkML metamodel.
  *
  * Parsing alone would be too weak a check. A handful of constructs make Mermaid throw a whole
  * statement away without reporting anything. So, we also parse validator outputs to assert if the
  * diagram covers the intended entities, attributes, keys, cardinalities and labels.
  *
  * Requires the Mermaid install prepared by the `generator.jvm.test.mermaidValidator` mill task.
  * Without it the tests cancel, unless `CI` is set, in which case they fail.
  */
class ErDiagramMermaidSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  import ErDiagramMermaidSpec.*

  /** Schemas whose names Mermaid would choke on, quote away or silently reinterpret.
    */
  private val adversary: Seq[(String, String)] = Seq(
    "keywordNames" -> """  one:
                        |    attributes:
                        |      x:
                        |  many:
                        |    attributes:
                        |      x:
                        |  to:
                        |    attributes:
                        |      x:
                        |  class:
                        |    attributes:
                        |      x:
                        |  style:
                        |    attributes:
                        |      x:
                        |  end:
                        |    attributes:
                        |      x:
                        |  u:
                        |    attributes:
                        |      x:
                        |""".stripMargin,
    // `"`, `%` and `\` cannot appear inside a quoted entity name at all, and there is no escape for
    // them.
    "unrepresentableInNames" -> """  A:
                                  |    alias: say "hi"
                                  |    attributes:
                                  |      x:
                                  |  B:
                                  |    alias: 100% of it
                                  |    attributes:
                                  |      x:
                                  |  C:
                                  |    alias: back\slash
                                  |    attributes:
                                  |      x:
                                  |  D:
                                  |    alias: a name with spaces
                                  |    attributes:
                                  |      x:
                                  |""".stripMargin,
    // A line holding `direction` plus a direction keyword is swallowed whole, quotes and all.
    "directionStatement" -> """  Root:
                              |    attributes:
                              |      a:
                              |        alias: direction LR
                              |        range: Other
                              |      b:
                              |        alias: goes direction BT now
                              |        range: Other
                              |  Other:
                              |    alias: x direction TB
                              |    attributes:
                              |      x:
                              |""".stripMargin,
    // Inside an entity block these lex as key constraints rather than as names.
    "keyConstraintNames" -> """  Root:
                              |    attributes:
                              |      pk:
                              |      fk:
                              |      uk:
                              |      PK:
                              |        alias: PK
                              |""".stripMargin,
    // A class with nothing but class-ranged slots has no attribute block, so its name has to stand
    // on its own - while still quoted.
    "quotedBareEntity" -> """  one:
                            |    attributes:
                            |      link:
                            |        range: many
                            |  many:
                            |    attributes:
                            |      x:
                            |""".stripMargin,
    "nonAscii" -> """  łączony (class):
                    |    attributes:
                    |      łączony [slot]:
                    |      Zamówienie:
                    |""".stripMargin,
    // A digit is legal in an attribute name, but not as its first character. An entity name may not
    // begin with one at all, since Mermaid's lexer reaches for `NUM` first.
    "leadingDigitNames" -> """  1class:
                             |    attributes:
                             |      1st slot:
                             |      2nd:
                             |  42:
                             |    attributes:
                             |      x:
                             |  4.2:
                             |    attributes:
                             |      x:
                             |""".stripMargin,
  )

  /** Every model in the catalogue, the adversary ones above, plus the metamodel.
    */
  private lazy val schemas: Seq[(String, SchemaView)] =
    ModelCatalogue.all.map(entry => (entry.name, entry.model)) ++
      adversary.map((name, classes) =>
        (
          name,
          SchemaIssues.orThrow(
            SchemaView.loadSchemaViewFromString(
              s"""id: https://neverblink.eu/test/$name/
                 |name: $name
                 |default_range: string
                 |types:
                 |  string:
                 |classes:
                 |$classes
                 |""".stripMargin,
            ),
          ),
        ),
      ) :+
      (
        "metamodel",
        SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta")),
      )

  /** Every diagram to check. All are generated with defaults except the last, which covers the
    * `optionalMarker = false` output kept for renderers older than Mermaid 11.16.
    */
  private lazy val diagrams: Seq[(String, ErDiagram)] =
    schemas.map((name, sv) => (name, ErDiagramGenerator(using sv).generate())) :+
      (
        "cardinalityWithoutOptionalMarker",
        ErDiagramGenerator(using ModelCatalogue.cardinality.model)
          .generate(ErDiagramGenerator.Options(optionalMarker = false)),
      )

  /** Generated diagrams, and what Mermaid made of them. Both are computed once: Node startup costs
    * far more than parsing every diagram in the catalogue.
    */
  private lazy val checked: Map[String, (ErDiagram, Parsed)] = {
    val dir = os.temp.dir(prefix = "linkml-er-diagram")
    diagrams.foreach((name, diagram) => os.write(dir / s"$name.mmd", diagram.print))
    val parsed = runValidator(dir)
    diagrams.map((name, diagram) =>
      (
        name,
        (
          diagram,
          parsed.getOrElse(
            name,
            fail(s"the Mermaid validator reported nothing for '$name'"),
          ),
        ),
      ),
    ).toMap
  }

  /** Cancel (or fail in CI) unless the Mermaid install is there. */
  private def requireValidator(): Unit = validator match {
    case Some(_) => ()
    case None =>
      val message =
        "Mermaid is not installed. Run `./mill generator.jvm.test.mermaidValidator` (needs Node.js " +
          "18+ and npm on the PATH) to prepare it."
      if inCi then fail(message) else cancel(message)
  }

  private def unquote(name: String): String =
    if name.length > 1 && name.startsWith("\"") && name.endsWith("\"") then
      name.drop(1).dropRight(1)
    else name

  /** What the generated diagram says Mermaid ought to have understood. */
  private def expected(diagram: ErDiagram): Parsed =
    Parsed(
      name = "",
      ok = true,
      error = None,
      entities = diagram.entities.map(e =>
        Entity(
          name = unquote(e.name),
          attributes = e.attributes.map(a =>
            Attribute(
              dataType = a.dataType + (if a.multivalued then "[]" else "") + (if a.optional then "?"
                                                                              else ""),
              name = a.name,
              keys = a.keys.map(_.toString),
            ),
          ),
        ),
      ),
      relationships = diagram.relationships.map(r =>
        Relationship(
          from = unquote(r.from),
          to = unquote(r.to),
          label = unquote(ErName.label(r.label)),
          fromCardinality = cardinalityTokens(r.fromCardinality),
          toCardinality = cardinalityTokens(r.toCardinality),
          identifying = r.identifying,
        ),
      ),
    )

  "ErDiagramGenerator" should {
    for (name, _) <- diagrams do
      s"generate a diagram that Mermaid reads back exactly for '$name'" in {
        processSkip(name, "")
        requireValidator()
        val (diagram, parsed) = checked(name)

        withClue(
          s"Mermaid rejected the diagram:\n${diagram.print}\n${parsed.error.getOrElse("")}\n",
        ) {
          parsed.ok shouldBe true
        }
        val want = expected(diagram)
        withClue(s"Mermaid read the diagram differently than intended:\n${diagram.print}\n") {
          // Mermaid keeps entities in source order and we emit them sorted, so these line up
          // without further sorting - and if they ever stop lining up, that is worth knowing.
          parsed.entities shouldBe want.entities
          parsed.relationships shouldBe want.relationships
        }
      }
  }
}

private object ErDiagramMermaidSpec {

  /** One entity as Mermaid understood it. */
  case class Attribute(dataType: String, name: String, keys: Seq[String])
  case class Entity(name: String, attributes: Seq[Attribute])
  case class Relationship(
      from: String,
      to: String,
      label: String,
      fromCardinality: String,
      toCardinality: String,
      identifying: Boolean,
  )

  /** One line of `validate.mjs` output. The defaults matter: a line reporting a parse failure
    * carries nothing but the name and the error.
    */
  case class Parsed(
      name: String,
      ok: Boolean,
      error: Option[String] = None,
      entities: Seq[Entity] = Nil,
      relationships: Seq[Relationship] = Nil,
  )

  private given codec: JsonValueCodec[Parsed] = JsonCodecMaker.make

  /** Mermaid's internal name for each cardinality glyph pair. */
  val cardinalityTokens: Map[ErCardinality, String] = Map(
    ErCardinality.zeroOrOne -> "ZERO_OR_ONE",
    ErCardinality.exactlyOne -> "ONLY_ONE",
    ErCardinality.zeroOrMore -> "ZERO_OR_MORE",
    ErCardinality.oneOrMore -> "ONE_OR_MORE",
  )

  /** Directory holding `validate.mjs` and its `node_modules`, prepared by the mill task. */
  val validator: Option[os.Path] =
    Option(System.getenv("LINKML_MERMAID_VALIDATOR"))
      .filter(_.nonEmpty)
      .map(os.Path(_, os.pwd))
      .filter(dir => os.exists(dir / "node_modules" / "mermaid") && os.exists(dir / "validate.mjs"))

  val inCi: Boolean = Option(System.getenv("CI")).exists(_.nonEmpty)

  /** Run every `.mmd` file in [[dir]] through Mermaid, keyed by file name. */
  def runValidator(dir: os.Path): Map[String, Parsed] = {
    val script = validator.getOrElse(throw IllegalStateException("no Mermaid validator"))
    val out = dir / "results.ndjson"
    val result = os.call(
      ("node", (script / "validate.mjs").toString, dir.toString, out.toString),
      cwd = script,
      check = false,
      stderr = os.Pipe,
    )
    if result.exitCode != 0 then
      throw RuntimeException(
        s"the Mermaid validator exited with ${result.exitCode}:\n${result.err.text()}",
      )
    os.read.lines(out)
      .filter(_.nonEmpty)
      .map(line => readFromString[Parsed](line))
      .map(parsed => (parsed.name, parsed))
      .toMap
  }
}
