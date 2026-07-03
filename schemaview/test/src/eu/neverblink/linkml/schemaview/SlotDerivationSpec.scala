package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import SchemaViewSpec.{compact, reference}

class SlotDerivationSpec extends AnyWordSpec, Matchers {

  "SlotDerivation" should {
    "inherit slot slots from class ancestors" in {
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
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.description shouldBe Some("Base description")
    }

    "override slot slots from class ancestors with child class slot usage" in {
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
        slotUsage = Map(slot.copy(description = Some("Child description")).compact),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.description shouldBe Some("Child description")
    }

    "ignore schema-level slots if slot comes from an attribute" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        identifier = true,
        range = Some(Reference("child")),
      )

      val child = ClassDefinitionImpl(
        name = "child",
        attributes = Map(
          SlotDefinitionImpl(
            name = "slot1",
            description = Some("Attribute description"),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.identifier shouldBe false
      result.description shouldBe Some("Attribute description")
    }

    "override is_a with mixins" in {
      val base = ClassDefinitionImpl(
        name = "base",
        attributes = Map(
          SlotDefinitionImpl(
            name = "slot1",
            description = Some("Base description"),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val mixin = ClassDefinitionImpl(
        name = "mixin",
        mixin = true,
        attributes = Map(
          SlotDefinitionImpl(
            name = "slot1",
            description = Some("Mixin description"),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val child = ClassDefinitionImpl(
        name = "child",
        isA = Some(base.reference),
        mixins = Seq(mixin.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          classes = Map(base.compact, mixin.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.description shouldBe Some("Mixin description")
    }

    "override attributes with slot_usage" in {
      val base = ClassDefinitionImpl(
        name = "base",
        attributes = Map(
          SlotDefinitionImpl(
            name = "slot1",
            description = Some("Base description"),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val child = ClassDefinitionImpl(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinitionImpl(
            name = "slot1",
            description = Some("Slot usage description"),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.description shouldBe Some("Slot usage description")
    }

    "merge Seq slots" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        notes = Seq("note 2"),
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
            notes = Seq("note 1"),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.notes shouldBe Seq("note 1", "note 2")
    }

    "not duplicate Seqs if the contents are identical" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        notes = Seq("note 1"),
        range = Some(Reference("base")),
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
            notes = Seq("note 1"),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.notes shouldBe Seq("note 1")
    }

    "merge Map slots" in {
      val ann1 =
        AnnotationImpl(
          extensionTag = UriOrCurie("ann1"),
          extensionValue = LinkmlAny("annotation 1"),
        )
      val ann2 =
        AnnotationImpl(
          extensionTag = UriOrCurie("ann2"),
          extensionValue = LinkmlAny("annotation 2"),
        )

      val slot = SlotDefinitionImpl(
        name = "slot1",
        annotations = Map("ann1" -> ann1),
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
            annotations = Map("ann2" -> ann2),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.annotations.values should contain theSameElementsAs Seq(ann1, ann2)
      result.annotations.keys should contain theSameElementsAs Seq("ann1", "ann2")
    }

    "not duplicate Map slots if the contents are identical" in {
      val ann1 =
        AnnotationImpl(
          extensionTag = UriOrCurie("ann1"),
          extensionValue = LinkmlAny("annotation 1"),
        )

      val slot = SlotDefinitionImpl(
        name = "slot1",
        annotations = Map("ann1" -> ann1),
        range = Some(Reference("base")),
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
            annotations = Map("ann1" -> ann1.copy()),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.annotations.values should contain theSameElementsAs Seq(ann1)
      result.annotations.keys should contain theSameElementsAs Seq("ann1")
    }

    "combine boolean values" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        identifier = true,
        range = Some(Reference("child")),
      )

      val child = ClassDefinitionImpl(
        name = "child",
        slots = Seq(slot.reference),
        slotUsage = Map(
          SlotDefinitionImpl(
            name = "slot1",
            identifier = false,
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.identifier shouldBe true
    }

    "not inherit non-inheritable values from parent slots " in {
      val slotParent = SlotDefinitionImpl(
        name = "slotParent",
        description = Some("Base description"),
        range = Some(Reference("base")),
      )

      val slotChild = SlotDefinitionImpl(
        name = "slotChild",
        isA = Some(slotParent.reference),
        range = Some(Reference("base")),
      )

      val base = ClassDefinitionImpl(
        name = "base",
        slots = Seq(slotChild.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slotChild.compact, slotParent.compact),
          classes = Map(base.compact),
        ),
      )

      val result = sv.classes("base").derivedAttributes("slotChild").slot
      result.description shouldBe None
    }

    "inherit inheritable values from parent slots" in {
      val slotParent = SlotDefinitionImpl(
        name = "slotParent",
        pattern = Some("Some pattern"),
        range = Some(Reference("base")),
      )

      val slotChild = SlotDefinitionImpl(
        name = "slotChild",
        isA = Some(slotParent.reference),
        range = Some(Reference("base")),
      )

      val base = ClassDefinitionImpl(
        name = "base",
        slots = Seq(slotChild.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slotChild.compact, slotParent.compact),
          classes = Map(base.compact),
        ),
      )

      val result = sv.classes("base").derivedAttributes("slotChild").slot
      result.pattern shouldBe Some("Some pattern")
    }

    "not apply slot usages to slots with different ids" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        title = Some("Slot 1"),
        range = Some(Reference("child")),
      )

      val slot2 = SlotDefinitionImpl(
        name = "slot2",
        title = Some("Slot 2"),
        range = Some(Reference("child")),
      )

      val child = ClassDefinitionImpl(
        name = "child",
        slots = Seq(slot.reference, slot2.reference),
        slotUsage = Map(
          SlotDefinitionImpl(
            name = "slot1",
            identifier = true,
            range = Some(Reference("child")),
          ).compact,
          SlotDefinitionImpl(
            name = "slot2",
            description = Some("desc"),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact, slot2.compact),
          classes = Map(child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.identifier shouldBe true
      result.description shouldBe None
      result.title shouldBe Some("Slot 1")

      val result2 = sv.classes("child").derivedAttributes("slot2").slot
      result2.identifier shouldBe false
      result2.description shouldBe Some("desc")
      result2.title shouldBe Some("Slot 2")
    }

    "infer the slot's URI" in {
      val slot = SlotDefinitionImpl(
        name = "slot1",
        title = Some("Slot 1"),
        range = Some(Reference("child")),
      )
      val child = ClassDefinitionImpl(
        name = "child",
        slots = Seq(slot.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinitionImpl(
          name = "",
          id = Uri("https://neverblink.eu/test"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact),
        ),
      )
      sv.classes("child").derivedAttributes("slot1")
        .uriOrCurie shouldBe UriOrCurie("https://neverblink.eu/test/slot1")
    }
  }
}
