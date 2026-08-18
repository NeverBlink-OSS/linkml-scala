package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LowestCommonAncestorSpec extends AnyWordSpec, Matchers {
  "SchemaView.lowestCommonAncestor" should {
    val schema: String =
      """id: urn:lcaTest
        |name: lcaTest
        |
        |classes:
        |  Root:
        |  ABC:
        |    is_a: Root
        |  AB:
        |    is_a: ABC
        |  RootMix:
        |    mixin: true
        |  Mix:
        |    mixin: true
        |    is_a: RootMix
        |  AltMix:
        |    mixin: true
        |    is_a: RootMix
        |  A:
        |    is_a: AB
        |    mixins: [ Mix ]
        |  B:
        |    is_a: AB
        |  C:
        |    is_a: ABC
        |    mixins: [ Mix ]
        |  D:
        |    is_a: Root
        |    mixins: [ AltMix ]
        |  E:
        |""".stripMargin

    val sv: SchemaView =
      SchemaView.loadSchemaViewFromString(schema).getOrElse(throw RuntimeException(""))

    val A: ClassView = sv.classes("A")
    val B: ClassView = sv.classes("B")
    val C: ClassView = sv.classes("C")
    val D: ClassView = sv.classes("D")
    val E: ClassView = sv.classes("E")

    val AB: ClassView = sv.classes("AB")
    val ABC: ClassView = sv.classes("ABC")
    val Mix: ClassView = sv.classes("Mix")

    val Root: ClassView = sv.classes("Root")
    val RootMix: ClassView = sv.classes("RootMix")

    "given A, get A" in {
      sv.lowestCommonAncestors(Seq(A)) shouldBe Seq(A)
    }
    "given A, B, get AB" in {
      sv.lowestCommonAncestors(Seq(A, B)) shouldBe Seq(AB)
    }
    "given A, B, C, get ABC" in {
      sv.lowestCommonAncestors(Seq(A, B, C)) shouldBe Seq(ABC)
    }
    "given A, B, C, D, get Root" in {
      sv.lowestCommonAncestors(Seq(A, B, C, D)) shouldBe Seq(Root)
    }
    "given A, B, C, D, E, get nothing" in {
      sv.lowestCommonAncestors(Seq(A, B, C, D, E)) shouldBe Seq()
    }
    "given A, C, get ABC, Mix" in {
      sv.lowestCommonAncestors(Seq(A, C)) should contain theSameElementsAs Seq(ABC, Mix)
    }
    "given A, C, D, get Root, RootMix" in {
      sv.lowestCommonAncestors(Seq(A, C, D)) should contain theSameElementsAs Seq(Root, RootMix)
    }
    "given A, AB, get AB" in {
      sv.lowestCommonAncestors(Seq(A, AB)) should contain theSameElementsAs Seq(AB)
    }
    "given A, ABC, get ABC" in {
      sv.lowestCommonAncestors(Seq(A, ABC)) should contain theSameElementsAs Seq(ABC)
    }
  }

  "Metamodel.lowestCommonAncestor" should {
    val sv = SchemaView.loadSchemaViewFromUri("linkml:meta").getOrElse(throw RuntimeException(""))
    "given Class, Slot, get Definition" in {
      sv.lowestCommonAncestors(
        Seq(
          sv.classes("class_definition"),
          sv.classes("slot_definition"),
        ),
      ).map(_.name) shouldBe Seq(
        "definition",
      )
    }

    "given Class, Slot, Type, get Element" in {
      sv.lowestCommonAncestors(
        Seq(
          sv.classes("class_definition"),
          sv.classes("slot_definition"),
          sv.classes("type_definition"),
        ),
      ).map(_.name) shouldBe Seq("element")
    }

    "given Slot, Type, get Element, Expression" in {
      sv.lowestCommonAncestors(
        Seq(
          sv.classes("slot_definition"),
          sv.classes("type_definition"),
        ),
      ).map(_.name) should contain theSameElementsAs Seq("element", "expression")
    }

    "given Enum, PermissibleValue, get Extensible, Annotatable, CommonMetadata" in {
      sv.lowestCommonAncestors(
        Seq(
          sv.classes("enum_definition"),
          sv.classes("permissible_value"),
        ),
      ).map(_.name) should contain theSameElementsAs Seq(
        "extensible",
        "annotatable",
        "common_metadata",
      )
    }

    "given Slot, AnonSlot, get SlotExpr, Extensible, Annotatable, CommonMetadata" in {
      sv.lowestCommonAncestors(
        Seq(
          sv.classes("slot_definition"),
          sv.classes("anonymous_slot_expression"),
        ),
      ).map(_.name) should contain theSameElementsAs Seq(
        "slot_expression",
        "annotatable",
        "extensible",
        "common_metadata",
      )
    }
  }
}
