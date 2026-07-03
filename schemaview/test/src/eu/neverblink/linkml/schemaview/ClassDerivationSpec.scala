package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.{Reference, UriOrCurie, Uri}
import eu.neverblink.linkml.schemaview.SchemaViewSpec.{compact, reference}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ClassDerivationSpec extends AnyWordSpec, Matchers {
  "ClassDerivation" should {
    "inline slots as attributes" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        description = Some("Base description"),
        range = Some(Reference("child")),
      )

      val child = ClassDefinitionImpl(
        name = "child",
        slots = Seq(slot.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact),
        ),
      )

      val result = sv.classes("child")
      result.derivedAttributes("slot1").definingSchema shouldBe sv.root
      result.derivedAttributes("slot1").slot.description shouldBe Some("Base description")
      result.derivedAttributes.keys should contain theSameElementsAs Seq("slot1")
    }

    "inherit slots as attributes" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        description = Some("Base description"),
        range = Some(Reference("child")),
      )

      val base = ClassDefinitionImpl(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinitionImpl(
        name = "child",
        isA = Some(base.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact, base.compact),
        ),
      )

      val result = sv.classes("child")
      result.derivedAttributes("slot1").slot.description shouldBe Some("Base description")
      result.derivedAttributes.keys should contain theSameElementsAs Seq("slot1")
    }

    "inherit and override slot slots as attributes" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        identifier = true,
        description = Some("Base description"),
        range = Some(Reference("child")),
      )

      val base = ClassDefinitionImpl(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinitionImpl(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinitionImpl(
            name = "slot1",
            description = Some("Child description"),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact, base.compact),
        ),
      )

      val result = sv.classes("child")
      result.derivedAttributes("slot1").slot.description shouldBe Some("Child description")
      result.derivedAttributes("slot1").slot.identifier shouldBe true
      result.derivedAttributes.keys should contain theSameElementsAs Seq("slot1")
    }

    "merge slots and attributes into attributes" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        description = Some("Base description"),
        range = Some(Reference("base")),
      )

      val base = ClassDefinitionImpl(
        name = "base",
        slots = Seq(slot.reference),
        attributes = Map(
          SlotDefinitionImpl(
            name = "slot2",
            description = Some("Other description"),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact),
        ),
      )

      val result = sv.classes("base")
      result.derivedAttributes.keys should contain theSameElementsAs Seq("slot1", "slot2")
      result.derivedAttributes("slot1").slot.description shouldBe Some("Base description")
      result.derivedAttributes("slot2").slot.description shouldBe Some("Other description")
    }

    "derive the class's URI" in {
      val base = ClassDefinitionImpl(
        name = "base",
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test"),
          classes = Map(base.compact),
        ),
      )
      val result = sv.classes("base")
      result.uriOrCurie shouldBe UriOrCurie("https://neverblink.eu/test/Base")
    }
  }
}
