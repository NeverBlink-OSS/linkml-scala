package eu.neverblink.linkml.generator.erdiagram

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Additional tests for the format of the ER diagram output.
  */
class ErDiagramWriterSpec extends AnyWordSpec, Matchers {

  private val header = "%% GENERATED FROM LINKML\nerDiagram\n"

  private def entity(name: String, attributes: ErAttribute*) =
    ErEntity(name, attributes.toSeq)

  private def attribute(dataType: String, name: String, keys: ErKey*) =
    ErAttribute(dataType, name, keys.toSeq, multivalued = false, optional = false)

  private def relationship(from: String, to: String, label: String) =
    ErRelationship(
      from = from,
      to = to,
      fromCardinality = ErCardinality.exactlyOne,
      toCardinality = ErCardinality.zeroOrOne,
      identifying = true,
      label = label,
    )

  "ErDiagram.writeTo" should {
    "write only the header for an empty diagram" in {
      // The trailing newline is the document terminator, so an empty body leaves a blank line.
      ErDiagram(Nil, Nil).print shouldBe header + "\n"
    }

    "write an attribute-less entity as a bare name" in {
      ErDiagram(Seq(entity("Foo")), Nil).print shouldBe header + "  Foo\n"
    }

    "indent an entity's attributes inside its block" in {
      val diagram = ErDiagram(
        Seq(entity("Foo", attribute("string", "bar"), attribute("integer", "baz"))),
        Nil,
      )
      diagram.print shouldBe
        header +
        """  Foo {
          |    string bar
          |    integer baz
          |  }
          |""".stripMargin
    }

    "leave no trailing space on an attribute without keys" in {
      // The key list is optional and comes last, so this is where a stray separator would show up.
      val lines = ErDiagram(Seq(entity("Foo", attribute("string", "bar"))), Nil).print.linesIterator
      lines.toSeq shouldBe Seq(
        "%% GENERATED FROM LINKML",
        "erDiagram",
        "  Foo {",
        "    string bar",
        "  }",
      )
    }

    "write key constraints after the attribute name" in {
      val diagram = ErDiagram(Seq(entity("Foo", attribute("string", "id", ErKey.PK))), Nil)
      diagram.print should include("    string id PK\n")
    }

    "render the type's array and optional markers together, on the type" in {
      val attr = ErAttribute("string", "bar", Nil, multivalued = true, optional = true)
      ErDiagram(Seq(entity("Foo", attr)), Nil).print should include("    string[]? bar\n")
    }

    "separate entities from relationships with a genuinely empty line" in {
      val diagram = ErDiagram(
        Seq(entity("Foo"), entity("Bar")),
        Seq(relationship("Foo", "Bar", "bar")),
      )
      diagram.print shouldBe
        header +
        "  Foo\n" +
        "  Bar\n" +
        "\n" + // no indentation here, or Mermaid sees trailing whitespace
        "  Foo ||--o| Bar : \"bar\"\n"
    }

    "not write a separator line when there are no relationships" in {
      ErDiagram(Seq(entity("Foo")), Nil).print shouldBe header + "  Foo\n"
    }

    "not write a separator line when there are no entities" in {
      ErDiagram(Nil, Seq(relationship("Foo", "Bar", "bar"))).print shouldBe
        header + "  Foo ||--o| Bar : \"bar\"\n"
    }

    "agree with the generator's own serialize" in {
      // writeTo drives both, so this only guards against the two paths drifting apart.
      val diagram = ErDiagram(
        Seq(entity("Foo", attribute("string", "id", ErKey.PK))),
        Seq(relationship("Foo", "Foo", "self")),
      )
      val streamed = new eu.neverblink.linkml.generator.util.StringSink
      diagram.writeTo(streamed)
      streamed.result shouldBe diagram.print
    }
  }
}
