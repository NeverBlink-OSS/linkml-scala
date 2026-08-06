package eu.neverblink.linkml.schemaview.expression

import fastparse.Parsed
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import StringInterpolationExpression.{Literal, Substitution}
import eu.neverblink.linkml.schemaview.{AttributeView, SchemaView}

class StringInterpolationExpressionSpec extends AnyWordSpec, Matchers {

  private val schema = """
    |id: urn:test
    |name: test
    |imports:
    | - linkml:types
    |classes:
    |  C1:
    |    attributes:
    |      reference_value:
    |        range: string
    |      class:
    |        range: string
    |      other:
    |        range: string
    |  C2:
    |    attributes:
    |      only_in_c2:
    |        range: string
  """.stripMargin

  private def parse(
      input: String,
  )(using AttributeView): Seq[StringInterpolationExpression.Element] =
    StringInterpolationExpression.parse(input) match {
      case Parsed.Success(expr, _) => expr.elements
      case f: Parsed.Failure => fail(s"Failed to parse '$input': ${f.trace().longAggregateMsg}")
    }

  private def parseError(input: String)(using AttributeView): String =
    StringInterpolationExpression.parse(input) match {
      case Parsed.Success(expr, _) => fail(s"Expected '$input' to fail, but got ${expr.elements}")
      case f: Parsed.Failure => f.trace().longAggregateMsg
    }

  "StringInterpolationExpression" should {
    given sv: SchemaView = SchemaView.loadSchemaViewFromString(schema)
    given context: AttributeView = sv.classes("C1").attributeViews("reference_value")
    val attrReferenceValue = sv.classes("C1").attributeViews("reference_value")
    val attrClass = sv.classes("C1").attributeViews("class")
    val attrOther = sv.classes("C1").attributeViews("other")

    "parse a string with no substitutions" in {
      parse("No 'default_range' is defined in the schema") shouldBe
        Seq(Literal("No 'default_range' is defined in the schema"))
    }

    "parse a substitution in the middle of a string" in {
      parse("Unknown reference to element '{reference_value}'") shouldBe Seq(
        Literal("Unknown reference to element '"),
        Substitution(attrReferenceValue),
        Literal("'"),
      )
    }

    "parse a substitution at the start of a string" in {
      parse("{other} is missing from the schema.") shouldBe Seq(
        Substitution(attrOther),
        Literal(" is missing from the schema."),
      )
    }

    "parse multiple substitutions" in {
      parse("{reference_value}-{other}") shouldBe Seq(
        Substitution(attrReferenceValue),
        Literal("-"),
        Substitution(attrOther),
      )
    }

    "parse adjacent substitutions" in {
      parse("{reference_value}{other}{other}") shouldBe Seq(
        Substitution(attrReferenceValue),
        Substitution(attrOther),
        Substitution(attrOther),
      )
    }

    "parse an empty string" in {
      parse("") shouldBe Seq()
    }

    "unescape doubled braces" in {
      parse("{{a}}") shouldBe Seq(Literal("{a}"))
    }

    "merge escapes into the surrounding literal" in {
      parse("a {{b}} {class} d") shouldBe Seq(
        Literal("a {b} "),
        Substitution(attrClass),
        Literal(" d"),
      )
    }

    "parse a string that is only a substitution" in {
      parse("{reference_value}") shouldBe Seq(Substitution(attrReferenceValue))
    }

    "unescape braces around a substitution" in {
      parse("{{{reference_value}}}") shouldBe Seq(
        Literal("{"),
        Substitution(attrReferenceValue),
        Literal("}"),
      )
    }

    "unescape a brace on its own" in {
      parse("{{") shouldBe Seq(Literal("{"))
      parse("}}") shouldBe Seq(Literal("}"))
    }

    "resolve slots against the defining class of the context" in {
      given c2: AttributeView = sv.classes("C2").attributeViews("only_in_c2")
      parse("{only_in_c2}") shouldBe Seq(Substitution(c2))
    }

    "reject a slot that belongs to a different class" in {
      given c2: AttributeView = sv.classes("C2").attributeViews("only_in_c2")
      parseError("{reference_value}") should include("reference_value")
    }

    "reject an unknown slot name, naming the slot and the class" in {
      val error = parseError("Hello {nope}")
      error should include("nope")
      error should include("C1")
    }

    "report the first unknown slot when there are several" in {
      parseError("{nope1} and {nope2}") should include("nope1")
    }

    "reject a slot name that is not trimmed" in {
      parseError("{ reference_value }") should include("reference_value")
    }

    "reject a lone closing brace" in {
      val error = parseError("Hello }")
      error should include("end-of-input")
      error should include("found \"}\"")
    }

    "reject an unclosed substitution" in {
      // The cut in `substitution` keeps the error inside it, rather than reporting it as
      // "expected end-of-input" at the opening brace.
      parseError("Hello {name") should include("substitution:1:7 / \"}\":1:12")
    }

    "reject an empty substitution" in {
      parseError("Hello {}") should include("substitution:1:7 / a slot name:1:8")
    }
  }
}
