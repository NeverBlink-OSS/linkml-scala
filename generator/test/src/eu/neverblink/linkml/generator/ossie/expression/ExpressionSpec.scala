package eu.neverblink.linkml.generator.ossie.expression

import eu.neverblink.linkml.runtime.LinkmlAny
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.virtuslab.yaml.{NodeOps, StringNode}

/** Tests for the Ossie expression language parsing and rendering. */
class ExpressionSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {

  import Expression.*

  // How many times to run each property test (scalacheck)
  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 1000)

  private def parsed(text: String): Expression =
    Expression.parse(text).getOrElse(fail(s"did not parse: '$text'"))

  println(StringNode("2020-01-01").asYaml.strip())

  "rendering" should {
    "write a member as a dotted pair" in {
      Member(Ref("Person"), "name").render shouldBe "Person.name"
    }

    "write an IN list with the values quoted" in {
      InList(Ref("Status"), Seq(Literal.Text("ALIVE"), Literal.Text("DEAD"))).render shouldBe
        "Status IN ('ALIVE', 'DEAD')"
    }

    "double an apostrophe inside a value" in {
      InList(Ref("Kind"), Seq(Literal.Text("it's"))).render shouldBe "Kind IN ('it''s')"
    }

    "write a bare number and a quoted string differently" in {
      AtLeast(Ref("Integer"), Literal.Number("5")).render shouldBe "Integer >= 5"
      AtLeast(Ref("Date"), Literal.Text("2020-01-01")).render shouldBe "Date >= '2020-01-01'"
    }

    "write a pattern as a REGEXP_LIKE call" in {
      RegexpLike(Ref("String"), Literal.Text("^[A-Z]$")).render shouldBe
        "REGEXP_LIKE(String, '^[A-Z]$')"
    }
  }

  "parsing" should {
    "read each shape back" in {
      parsed("Person.name") shouldBe Member(Ref("Person"), "name")
      parsed("Status IN ('ALIVE', 'DEAD')") shouldBe
        InList(Ref("Status"), Seq(Literal.Text("ALIVE"), Literal.Text("DEAD")))
      parsed("Integer >= 5") shouldBe AtLeast(Ref("Integer"), Literal.Number("5"))
      parsed("Integer <= -1") shouldBe AtMost(Ref("Integer"), Literal.Number("-1"))
      parsed("REGEXP_LIKE(S, '^x')") shouldBe RegexpLike(Ref("S"), Literal.Text("^x"))
    }

    "unescape a doubled apostrophe" in {
      parsed("Kind IN ('it''s')") shouldBe InList(Ref("Kind"), Seq(Literal.Text("it's")))
    }

    "read an empty IN list" in {
      parsed("Kind IN ()") shouldBe InList(Ref("Kind"), Nil)
    }

    "tell a quoted bound from a bare one" in {
      parsed("Date >= '2020-01-01'") shouldBe AtLeast(Ref("Date"), Literal.Text("2020-01-01"))
      parsed("Integer >= 5") shouldBe AtLeast(Ref("Integer"), Literal.Number("5"))
    }

    "report the name it actually found" in {
      parsed("IntegerX >= 5").ref shouldBe Ref("IntegerX")
      Constraints.from(Ref("Integer"), Seq(parsed("IntegerX >= 5"))) shouldBe Constraints()
    }

    "refuse a shape it does not write" in {
      Expression.parse("Integer <> 3") shouldBe None
      Expression.parse("COUNT(Integer) > 1") shouldBe None
      Expression.parse("Integer >= ") shouldBe None
      Expression.parse("") shouldBe None
    }

    "refuse an unterminated literal" in {
      Expression.parse("Kind IN ('a)") shouldBe None
      Expression.parse("REGEXP_LIKE(S, 'x)") shouldBe None
    }

    "refuse a bound that is neither a number nor a literal" in {
      Expression.parse("Integer >= abc") shouldBe None
    }

    "refuse a name that is not a bare identifier" in {
      Expression.parse("Order.line.nr") shouldBe None
      Expression.parse("'Order'.nr") shouldBe None
      Expression.parse("Order line >= 5") shouldBe None
    }

    "not be fooled by REGEXP_LIKE used as a name" in {
      parsed("REGEXP_LIKE.x") shouldBe Member(Ref("REGEXP_LIKE"), "x")
    }
  }

  "a LinkML value" should {
    "go in bare when it is a number, quoted when it is not" in {
      Literal.fromLinkml(LinkmlAny("5")) shouldBe Some(Literal.Number("5"))
      Literal.fromLinkml(LinkmlAny("abc")) shouldBe Some(Literal.Text("abc"))
    }

    "go in bare even when the YAML had it quoted" in {
      Literal.fromLinkml(LinkmlAny("\"5\"")) shouldBe Some(Literal.Number("5"))
    }

    "unescape the YAML rather than just trimming the quotes" in {
      Literal.fromLinkml(LinkmlAny("\"a\\\"b\"")) shouldBe Some(Literal.Text("a\"b"))
      Literal.fromLinkml(LinkmlAny("\"a\\\\b\"")) shouldBe Some(Literal.Text("a\\b"))
    }

    "fold a wrapped scalar the way YAML does" in {
      Literal.fromLinkml(LinkmlAny("a\nb")) shouldBe Some(Literal.Text("a b"))
    }

    "be refused when it cannot be written as one" in {
      Literal.fromLinkml(LinkmlAny("")) shouldBe None
      Literal.fromLinkml(LinkmlAny("\"a\\nb\"")) shouldBe None
      Literal.fromLinkml(LinkmlAny("[1, 2]")) shouldBe None
    }
  }

  "the round trip" should {
    "parse back every expression it wrote" in {
      forAll(ExpressionGen.expression) { e =>
        Expression.parse(e.render) shouldBe Some(e)
      }
    }

    "accept whitespace it would not have written itself" in {
      forAll(ExpressionGen.expression) { e =>
        Expression.parse(ExpressionGen.spaced(e)) shouldBe Some(e)
      }
    }

    "parse back every literal that survives LinkML" in {
      forAll(ExpressionGen.linkmlSafeLiteral) { l =>
        Literal.fromLinkml(l.toLinkml) shouldBe Some(l)
      }
    }

    "keep constraints attached to the name they were written over" in {
      forAll(ExpressionGen.ref, ExpressionGen.ref, ExpressionGen.linkmlSafeLiteral) { (a, b, v) =>
        val written = Constraints(minimumValue = Some(v.toLinkml)).render(a)
        val read = Constraints.from(a, written.map(_.render).flatMap(Expression.parse))
        read.minimumValue.map(_.value) shouldBe Some(v.toLinkml.value)
        if a != b then
          Constraints.from(b, written.map(_.render).flatMap(Expression.parse)) shouldBe
            Constraints()
      }
    }

    "answer rather than throw, whatever it is handed" in {
      forAll(Gen.oneOf(Gen.asciiStr, Gen.alphaStr, Gen.identifier)) { s =>
        noException should be thrownBy Expression.parse(s)
      }
    }
  }
}
