package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ClassViewSpec extends AnyWordSpec, Matchers {
  private def load(classes: String): SchemaView = SchemaIssues.orThrow(
    SchemaView.loadSchemaViewFromString(
      """id: https://example.org/td/
        |name: td
        |prefixes:
        |  ex: https://example.org/
        |  linkml: https://w3id.org/linkml/
        |imports:
        |  - linkml:types
        |default_prefix: ex
        |enums:
        |  Kinds:
        |    permissible_values:
        |      A:
        |classes:
        |""".stripMargin + classes,
    ),
  )

  // A designated base class with one concrete subclass, for each designator range
  private def withRange(range: String, classUri: String = "ex:Sub"): SchemaView = load(
    s"""  Base:
       |    abstract: true
       |    attributes:
       |      kind:
       |        designates_type: true
       |        range: $range
       |  Sub:
       |    is_a: Base
       |    class_uri: $classUri
       |""".stripMargin,
  )

  private def values(range: String, classUri: String = "ex:Sub"): Seq[String] =
    withRange(range, classUri).classes("Sub").typeDesignatorValues

  "ClassView.typeDesignator" should {
    "find the designator slot, also when it is inherited" in {
      given sv: SchemaView = withRange("string")
      sv.classes("Base").typeDesignator.map(_.slot.name) shouldBe Some("kind")
      sv.classes("Sub").typeDesignator.map(_.slot.name) shouldBe Some("kind")
    }
    "be empty for a class without a designator" in {
      given sv: SchemaView = load("  Plain:\n    attributes:\n      x:\n        range: string\n")
      sv.classes("Plain").typeDesignator shouldBe None
    }
  }

  "ClassView.typeDesignatorValues" should {
    "be the class name for a string range" in {
      values("string") shouldBe Seq("Sub")
    }
    "be the class URI for a uri range" in {
      values("uri") shouldBe Seq("https://example.org/Sub")
    }
    "be the class CURIE for a curie range" in {
      values("curie") shouldBe Seq("ex:Sub")
    }
    "be the class URI and CURIE for a uriorcurie range" in {
      values("uriorcurie") shouldBe Seq("https://example.org/Sub", "ex:Sub")
    }
    "have one value for a uriorcurie range when the class URI has no prefix" in {
      values("uriorcurie", "Foo") shouldBe Seq("Foo")
    }
    "be empty for a type range other than a string or a URI" in {
      values("integer") shouldBe empty
    }
    "be empty for an enum range" in {
      values("Kinds") shouldBe empty
    }
    "be empty for a class without a designator" in {
      given sv: SchemaView = load("  Plain:\n    attributes:\n      x:\n        range: string\n")
      sv.classes("Plain").typeDesignatorValues shouldBe empty
    }
  }

  "ClassView.typeDesignatorMembers and isTypeDesignatorUnion" should {
    given sv: SchemaView = load(
      """  Abstract:
        |    abstract: true
        |    attributes:
        |      kind:
        |        designates_type: true
        |        range: string
        |  Mixin:
        |    mixin: true
        |  Middle:
        |    is_a: Abstract
        |  Leaf:
        |    is_a: Middle
        |    mixins:
        |      - Mixin
        |  AbstractLeaf:
        |    is_a: Middle
        |    abstract: true
        |  Empty:
        |    abstract: true
        |    attributes:
        |      kind:
        |        designates_type: true
        |        range: string
        |  Plain:
        |    attributes:
        |      x:
        |        range: string
        |""".stripMargin,
    )

    def members(name: String): Seq[String] = sv.classes(name).typeDesignatorMembers.map(_.name)

    "list the concrete descendants of an abstract class, skipping abstract ones" in {
      members("Abstract") shouldBe Seq("Leaf", "Middle")
      sv.classes("Abstract").isTypeDesignatorUnion shouldBe true
    }
    "list a concrete class together with its concrete descendants" in {
      members("Middle") shouldBe Seq("Leaf", "Middle")
      sv.classes("Middle").isTypeDesignatorUnion shouldBe true
    }
    "list only itself for a concrete class without descendants" in {
      members("Leaf") shouldBe Seq("Leaf")
      sv.classes("Leaf").isTypeDesignatorUnion shouldBe false
    }
    "be empty for an abstract class without concrete descendants" in {
      members("Empty") shouldBe empty
      sv.classes("Empty").isTypeDesignatorUnion shouldBe false
    }
    "be empty for a class without a designator" in {
      members("Plain") shouldBe empty
      members("Mixin") shouldBe empty
      sv.classes("Plain").isTypeDesignatorUnion shouldBe false
    }
  }

  "ClassView.allowsExtraSlots" should {
    "follow the class' own extra_slots, where an explicit allowed wins" in {
      val sv = load(
        """  Open:
          |    extra_slots:
          |      allowed: true
          |    attributes:
          |      a: {}
          |  Typed:
          |    extra_slots:
          |      range_expression:
          |        range: string
          |    attributes:
          |      a: {}
          |  ClosedTyped:
          |    extra_slots:
          |      allowed: false
          |      range_expression:
          |        range: string
          |    attributes:
          |      a: {}
          |  OpenChild:
          |    is_a: Open
          |  Plain:
          |    attributes:
          |      a: {}
          |""".stripMargin,
      )
      sv.classes("Open").allowsExtraSlots shouldBe true
      sv.classes("Typed").allowsExtraSlots shouldBe true
      sv.classes("ClosedTyped").allowsExtraSlots shouldBe false
      sv.classes("OpenChild").allowsExtraSlots shouldBe false
      sv.classes("Plain").allowsExtraSlots shouldBe false
    }
  }
}
