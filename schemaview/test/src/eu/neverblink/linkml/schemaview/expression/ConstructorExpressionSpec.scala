package eu.neverblink.linkml.schemaview.expression

import eu.neverblink.linkml.runtime.Curie
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

    lazy val sv = SchemaView.loadSchemaViewFromString(schema)
    lazy val e1 = sv.enums("E1")
    lazy val otherEnum = sv.enums("Other_Enum")

    "resolve a permissible value" in {
      ConstructorExpression.evaluateEnum("E1(V2)", e1).text shouldBe "V2"
    }

    "return the full permissible value, not just its text" in {
      ConstructorExpression.evaluateEnum("E1(V1)", e1).meaning shouldBe
        Some(Curie("ex:FirstValue"))
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
}
