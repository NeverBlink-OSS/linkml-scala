package eu.neverblink.linkml.generator.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class IndentSpec extends AnyWordSpec, Matchers {
  import IndentSpec.*
  "indent interpolator" should {
    "interpolate strings" in {
      val string = "string"
      indent"""
              |some $string
              |""".stripMargin shouldBe
        """
          |some string
          |""".stripMargin
    }

    "interpolate multiline strings" in {
      val string = "string\nother string"
      indent"""
              |some $string
              |""".stripMargin shouldBe
        """
          |some string
          |other string
          |""".stripMargin
    }

    "strip leading/trailing whitespace from interpolated strings" in {
      val string = "  string  "
      indent"""
              |some $string
              |""".stripMargin shouldBe
        """
          |some string
          |""".stripMargin
    }

    "indent multiline interpolated strings" in {
      val string = "string\nother string"
      indent"""
              |some {
              |  $string
              |}
              |""".stripMargin shouldBe
        """
          |some {
          |  string
          |  other string
          |}
          |""".stripMargin
    }

    "allow nested indents" in {
      val innerMost =
        """string
          |other string
          |""".stripMargin

      val inner =
        indent"""
          |some {
          |  $innerMost
          |}
          |""".stripMargin

      val outer =
        indent"""
          |two of {
          |  $inner
          |  =
          |  $inner
          |}
          |""".stripMargin

      outer shouldBe
        """
          |two of {
          |  some {
          |    string
          |    other string
          |  }
          |  =
          |  some {
          |    string
          |    other string
          |  }
          |}
          |""".stripMargin

    }

    "allow numbers" in {
      indent"""
        |${123}
        |  ${123.456}
        |""".stripMargin shouldBe
        """
          |123
          |  123.456
          |""".stripMargin
    }

    "not allow Any" in {
      assertDoesNotCompile("""indent"${NonPrintableClass()}" """)
    }

    "allow printable classes" in {
      indent"""
        |scope {
        |  ${PrintableClass("variable", "value")}
        |}
        |""".stripMargin shouldBe
        """
          |scope {
          |  some code {
          |    variable = value
          |  }
          |}
          |""".stripMargin
    }

    "allow escapes" in {
      indent"""
              |a\nb
              |""".stripMargin shouldBe
        """
          |a
          |b
          |""".stripMargin
    }

    "translate Unit to empty string" in {
      indent"a ${()} b".stripMargin shouldBe "a  b"
    }
  }
}

object IndentSpec {
  case class NonPrintableClass()

  case class PrintableClass(name: String, value: String) extends Printable:
    override def print: String =
      indent"""
        |some code {
        |  $name = $value
        |}
        |""".stripMargin
}
