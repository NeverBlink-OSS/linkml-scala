package eu.neverblink.linkml.schemaview.expression

import eu.neverblink.linkml.schemaview.SchemaIssues

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.linkml.schemaview.SchemaView
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConstructorExpressionSpec extends AnyWordSpec, Matchers {

  "ConstructorExpression.evaluateEnum" should {
    val schema =
      """id: urn:test
        |name: test
        |
        |prefixes:
        |  ex: https://example.org/
        |default_prefix: ex
        |
        |enums:
        |  E1:
        |    permissible_values:
        |      V1:
        |        meaning: ex:FirstValue
        |      V2:
        |      V 3:
        |  Other_Enum:
        |    permissible_values:
        |      V1:
        |""".stripMargin

    lazy val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schema))
    lazy val e1 = sv.enums("E1")
    lazy val otherEnum = sv.enums("Other_Enum")

    "resolve a permissible value" in {
      ConstructorExpression.evaluateEnum("E1(V2)", e1).text shouldBe "V2"
    }

    "return the full permissible value, not just its text" in {
      ConstructorExpression.evaluateEnum("E1(V1)", e1).meaning shouldBe
        Some(Uri("https://example.org/FirstValue"))
    }

    "accept enum names containing underscores" in {
      ConstructorExpression.evaluateEnum("Other_Enum(V1)", otherEnum).text shouldBe "V1"
    }

    "accept permissible values containing spaces" in {
      ConstructorExpression.evaluateEnum("E1(V 3)", e1).text shouldBe "V 3"
    }

    "reject an expression whose enum name does not match the range" in {
      val e = the[ConstructorExpression.EvaluationException] thrownBy
        ConstructorExpression.evaluateEnum("Other_Enum(V1)", e1)
      e.getMessage should include("does not match the expected enum type 'E1'")
    }

    "reject a value that is not in the enum" in {
      val e = the[ConstructorExpression.EvaluationException] thrownBy
        ConstructorExpression.evaluateEnum("E1(nope)", e1)
      e.getMessage should include("Value 'nope' not found in enum 'E1'")
    }

    "reject an empty value" in {
      the[ConstructorExpression.EvaluationException] thrownBy
        ConstructorExpression.evaluateEnum("E1()", e1)
    }

    "reject expressions that are not constructor calls" in {
      for expr <- Seq("V1", "E1", "E1(V1", "E1 V1)", "", "(V1)", "1E(V1)") do
        withClue(s"expression: '$expr'") {
          the[ConstructorExpression.EvaluationException] thrownBy
            ConstructorExpression.evaluateEnum(expr, e1)
        }
    }
  }

  "ConstructorExpression.parseString" should {
    import ConstructorExpression.StringConstructor

    "remove double or single quotes" in {
      ConstructorExpression.parseString("""string("en")""") shouldBe
        Some(StringConstructor("en", quoted = true))
      ConstructorExpression.parseString("string('en')") shouldBe
        Some(StringConstructor("en", quoted = true))
    }

    "accept a value without quotes, and mark it as such" in {
      ConstructorExpression.parseString("string(eu.neverblink.linkml)") shouldBe
        Some(StringConstructor("eu.neverblink.linkml", quoted = false))
    }

    "keep the text between the quotes as it is" in {
      ConstructorExpression.parseString("""string("J/(kg K)")""").map(_.value) shouldBe
        Some("J/(kg K)")
      ConstructorExpression.parseString("""string('say "hi"')""").map(_.value) shouldBe
        Some("say \"hi\"")
      ConstructorExpression.parseString("""string(" padded ")""").map(_.value) shouldBe
        Some(" padded ")
      ConstructorExpression.parseString("""string("")""") shouldBe
        Some(StringConstructor("", quoted = true))
    }

    "treat unmatched quotes as part of a value without quotes" in {
      ConstructorExpression.parseString("""string("en')""") shouldBe
        Some(StringConstructor("\"en'", quoted = false))
      ConstructorExpression.parseString("""string(")""") shouldBe
        Some(StringConstructor("\"", quoted = false))
    }

    "return None for other expressions" in {
      for expr <- Seq("en", "int(2)", "String(en)", "string(en", "string en)", "") do
        withClue(s"expression: '$expr'") {
          ConstructorExpression.parseString(expr) shouldBe None
        }
    }

    "reject other expressions when evaluating" in {
      val e = the[ConstructorExpression.EvaluationException] thrownBy
        ConstructorExpression.evaluateString("int(2)")
      e.getMessage should include("Invalid string constructor expression: 'int(2)'")
    }
  }

  "ConstructorExpression.evaluateInt" should {
    "evaluate integers with an optional sign" in {
      ConstructorExpression.evaluateInt("int(2)") shouldBe 2
      ConstructorExpression.evaluateInt("int(-17)") shouldBe -17
      ConstructorExpression.evaluateInt("int(+5)") shouldBe 5
    }

    "reject an integer that doesn't fit in an Int" in {
      val e = the[ConstructorExpression.EvaluationException] thrownBy
        ConstructorExpression.evaluateInt("int(2147483648)")
      e.getMessage should include("out of range")
    }

    "reject expressions that are not integer constructor calls" in {
      for expr <- Seq("2", "int()", "int(2.5)", "int(\"2\")", "integer(2)", "string(2)") do
        withClue(s"expression: '$expr'") {
          the[ConstructorExpression.EvaluationException] thrownBy
            ConstructorExpression.evaluateInt(expr)
        }
    }
  }

  "ConstructorExpression.evaluateBoolean" should {
    "evaluate true and false in any case" in {
      for expr <- Seq("true", "True", "TRUE", " true ") do
        ConstructorExpression.evaluateBoolean(expr) shouldBe true
      for expr <- Seq("false", "False") do
        ConstructorExpression.evaluateBoolean(expr) shouldBe false
    }

    "reject other values" in {
      for expr <- Seq("yes", "1", "bool(true)", "") do
        withClue(s"expression: '$expr'") {
          the[ConstructorExpression.EvaluationException] thrownBy
            ConstructorExpression.evaluateBoolean(expr)
        }
    }
  }
}
