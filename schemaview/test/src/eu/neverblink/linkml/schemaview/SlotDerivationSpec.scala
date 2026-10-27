package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import SchemaViewSpec.{compact, reference}

class SlotDerivationSpec extends AnyWordSpec, Matchers {
  private def ranges(names: String*): Seq[AnonymousSlotExpression] =
    names.map(n => AnonymousSlotExpression(range = Some(Reference(n))))

  /** Classes to use as ranges in [[ranges]] */
  private val rangeClasses: Seq[ClassDefinition] = Seq("a", "b", "c").map(ClassDefinition(_))

  private def schemaView(slots: Seq[SlotDefinition], classes: Seq[ClassDefinition]): SchemaView =
    SchemaView.single(
      SchemaDefinition(
        name = "",
        id = Uri("https://neverblink.eu/test/"),
        defaultRange = Some(Reference("base")),
        slotDefinitions = slots.map(_.compact).toMap,
        classes = (classes ++ rangeClasses).map(_.compact).toMap,
      ),
    )

  "SlotDerivation" should {
    "inherit slot slots from class ancestors" in {
      val slot = SlotDefinition(
        name = "slot1",
        description = Some(PlainText("Base description")),
        range = Some(Reference("child")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.description shouldBe Some(PlainText("Base description"))
    }

    "override slot slots from class ancestors with child class slot usage" in {
      val slot = SlotDefinition(
        name = "slot1",
        description = Some(PlainText("Base description")),
        range = Some(Reference("child")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(slot.copy(description = Some(PlainText("Child description"))).compact),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.description shouldBe Some(PlainText("Child description"))
    }

    "ignore schema-level slots if slot comes from an attribute" in {
      val slot = SlotDefinition(
        name = "slot1",
        identifier = true,
        range = Some(Reference("child")),
      )

      val child = ClassDefinition(
        name = "child",
        attributes = Map(
          SlotDefinition(
            name = "slot1",
            description = Some(PlainText("Attribute description")),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.identifier shouldBe false
      result.description shouldBe Some(PlainText("Attribute description"))
    }

    "override is_a with mixins" in {
      val base = ClassDefinition(
        name = "base",
        attributes = Map(
          SlotDefinition(
            name = "slot1",
            description = Some(PlainText("Base description")),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val mixin = ClassDefinition(
        name = "mixin",
        mixin = true,
        attributes = Map(
          SlotDefinition(
            name = "slot1",
            description = Some(PlainText("Mixin description")),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        mixins = Seq(mixin.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          classes = Map(base.compact, mixin.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.description shouldBe Some(PlainText("Mixin description"))
    }

    "override attributes with slot_usage" in {
      val base = ClassDefinition(
        name = "base",
        attributes = Map(
          SlotDefinition(
            name = "slot1",
            description = Some(PlainText("Base description")),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            description = Some(PlainText("Slot usage description")),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.description shouldBe Some(PlainText("Slot usage description"))
    }

    "merge Seq slots" in {
      val slot = SlotDefinition(
        name = "slot1",
        notes = Seq(PlainText("note 2")),
        range = Some(Reference("child")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            notes = Seq(PlainText("note 1")),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.notes shouldBe Seq(PlainText("note 1"), PlainText("note 2"))
    }

    "combine boolean slots so that a subclass accepts only values that its parents accept" in {
      val slot = SlotDefinition(
        name = "slot1",
        anyOf = ranges("base", "child", "grandchild"),
        allOf = ranges("base"),
        exactlyOneOf = ranges("base", "child"),
        noneOf = ranges("grandchild"),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            anyOf = ranges("base", "child"),
            allOf = ranges("child"),
            exactlyOneOf = ranges("base"),
          ).compact,
        ),
      )

      val grandchild = ClassDefinition(
        name = "grandchild",
        isA = Some(child.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            anyOf = ranges("base"),
            noneOf = ranges("child"),
          ).compact,
        ),
      )

      val sv = schemaView(Seq(slot), Seq(base, child, grandchild))

      val inBase = sv.classes("base").derivedAttributes("slot1").slot
      inBase.anyOf shouldBe ranges("base", "child", "grandchild")
      inBase.allOf shouldBe ranges("base")

      val inChild = sv.classes("child").derivedAttributes("slot1").slot
      // The members are a subset of the inherited ones, so the inherited list is not needed
      inChild.anyOf shouldBe ranges("base", "child")
      // An `exactly_one_of` subset can accept more values, so the inherited list is kept
      inChild.exactlyOneOf shouldBe ranges("base")
      inChild.allOf shouldBe ranges("child", "base") :+
        AnonymousSlotExpression(exactlyOneOf = ranges("base", "child"))
      // Not set in `slot_usage`, so inherited
      inChild.noneOf shouldBe ranges("grandchild")

      val inGrandchild = sv.classes("grandchild").derivedAttributes("slot1").slot
      inGrandchild.anyOf shouldBe ranges("base")
      inGrandchild.exactlyOneOf shouldBe ranges("base")
      inGrandchild.allOf shouldBe inChild.allOf
      inGrandchild.noneOf shouldBe ranges("child", "grandchild")
    }

    "keep the inherited any_of in all_of if a slot_usage adds new members to any_of" in {
      val slot = SlotDefinition(name = "slot1", anyOf = ranges("a", "b"))
      val base = ClassDefinition(name = "base", slots = Seq(slot.reference))
      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(SlotDefinition(name = "slot1", anyOf = ranges("a", "c")).compact),
      )

      val sv = schemaView(Seq(slot), Seq(base, child))

      val inChild = sv.classes("child").derivedAttributes("slot1").slot
      inChild.anyOf shouldBe ranges("a", "c")
      inChild.allOf shouldBe Seq(AnonymousSlotExpression(anyOf = ranges("a", "b")))
    }

    "keep the inherited exactly_one_of in all_of if a slot_usage adds new members to it" in {
      val slot = SlotDefinition(name = "slot1", exactlyOneOf = ranges("a", "b"))
      val base = ClassDefinition(name = "base", slots = Seq(slot.reference))
      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(SlotDefinition(name = "slot1", exactlyOneOf = ranges("a", "c")).compact),
      )

      val sv = schemaView(Seq(slot), Seq(base, child))

      val inChild = sv.classes("child").derivedAttributes("slot1").slot
      inChild.exactlyOneOf shouldBe ranges("a", "c")
      inChild.allOf shouldBe Seq(AnonymousSlotExpression(exactlyOneOf = ranges("a", "b")))
    }

    "require the any_of of both a mixin and the parent class" in {
      val slot = SlotDefinition(name = "slot1")
      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
        slotUsage = Map(SlotDefinition(name = "slot1", anyOf = ranges("a", "b")).compact),
      )
      val mixin = ClassDefinition(
        name = "mixin",
        mixin = true,
        slotUsage = Map(SlotDefinition(name = "slot1", anyOf = ranges("a", "c")).compact),
      )
      val combined = ClassDefinition(
        name = "combined",
        isA = Some(base.reference),
        mixins = Seq(mixin.reference),
      )

      val sv = schemaView(Seq(slot), Seq(base, mixin, combined))

      val inCombined = sv.classes("combined").derivedAttributes("slot1").slot
      inCombined.anyOf shouldBe ranges("a", "c")
      inCombined.allOf shouldBe Seq(AnonymousSlotExpression(anyOf = ranges("a", "b")))
    }

    "not repeat members of all_of and none_of that are inherited more than once" in {
      val slot = SlotDefinition(
        name = "slot1",
        allOf = ranges("base"),
        noneOf = ranges("a"),
      )
      val base = ClassDefinition(name = "base", slots = Seq(slot.reference))
      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            allOf = ranges("base", "child"),
            noneOf = ranges("a"),
          ).compact,
        ),
      )

      val sv = schemaView(Seq(slot), Seq(base, child))

      val inChild = sv.classes("child").derivedAttributes("slot1").slot
      inChild.allOf shouldBe ranges("base", "child")
      inChild.noneOf shouldBe ranges("a")
    }

    "not duplicate Seqs if the contents are identical" in {
      val slot = SlotDefinition(
        name = "slot1",
        notes = Seq(PlainText("note 1")),
        range = Some(Reference("base")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            notes = Seq(PlainText("note 1")),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.notes shouldBe Seq(PlainText("note 1"))
    }

    "merge Map slots" in {
      val ann1 =
        Annotation(
          extensionTag = UriOrCurie("https://neverblink.eu/test/ann1"),
          extensionValue = LinkmlAny("annotation 1"),
        )
      val ann2 =
        Annotation(
          extensionTag = UriOrCurie("https://neverblink.eu/test/ann2"),
          extensionValue = LinkmlAny("annotation 2"),
        )

      val slot = SlotDefinition(
        name = "slot1",
        annotations = Map("ann1" -> ann1),
        range = Some(Reference("child")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            annotations = Map("ann2" -> ann2),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
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

    "give slot_usage precedence over the slot for the same Map key" in {
      val slot = SlotDefinition(
        name = "slot1",
        annotations = Map(
          "ann1" -> Annotation(
            extensionTag = UriOrCurie("https://neverblink.eu/test/ann1"),
            extensionValue = LinkmlAny("from slot"),
          ),
        ),
        range = Some(Reference("base")),
      )
      val fromUsage = Annotation(
        extensionTag = UriOrCurie("https://neverblink.eu/test/ann1"),
        extensionValue = LinkmlAny("from slot_usage"),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            annotations = Map("ann1" -> fromUsage),
            range = Some(Reference("base")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(base.compact, child.compact),
        ),
      )

      sv.classes("child").derivedAttributes("slot1").slot.annotations shouldBe Map(
        "ann1" -> fromUsage,
      )
    }

    "not duplicate Map slots if the contents are identical" in {
      val ann1 =
        Annotation(
          extensionTag = UriOrCurie("https://neverblink.eu/test/ann1"),
          extensionValue = LinkmlAny("annotation 1"),
        )

      val slot = SlotDefinition(
        name = "slot1",
        annotations = Map("ann1" -> ann1),
        range = Some(Reference("base")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slot.reference),
      )

      val child = ClassDefinition(
        name = "child",
        isA = Some(base.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            annotations = Map("ann1" -> ann1.copy()),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
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
      val slot = SlotDefinition(
        name = "slot1",
        identifier = true,
        range = Some(Reference("child")),
      )

      val child = ClassDefinition(
        name = "child",
        slots = Seq(slot.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            identifier = false,
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.identifier shouldBe true
    }

    "keep `required` and `multivalued` when a slot_usage sets them to false" in {
      // The spec combines boolean metaslots with OR, so a slot_usage can't relax them.
      // LinkML-Python 1.12.1 makes both slots optional and single-valued here.
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://neverblink.eu/test/
            |name: test
            |default_range: string
            |types:
            |  string:
            |    base: str
            |classes:
            |  Base:
            |    slots:
            |      - name
            |      - tags
            |  Child:
            |    is_a: Base
            |    slot_usage:
            |      name:
            |        required: false
            |      tags:
            |        multivalued: false
            |slots:
            |  name:
            |    required: true
            |  tags:
            |    multivalued: true
            |""".stripMargin,
        ),
      )

      val attributes = sv.classes("Child").derivedAttributes
      attributes("name").slot.required shouldBe true
      attributes("tags").slot.multivalued shouldBe true
    }

    "not inherit non-inheritable values from parent slots " in {
      val slotParent = SlotDefinition(
        name = "slotParent",
        description = Some(PlainText("Base description")),
        range = Some(Reference("base")),
      )

      val slotChild = SlotDefinition(
        name = "slotChild",
        isA = Some(slotParent.reference),
        range = Some(Reference("base")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slotChild.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
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
      val slotParent = SlotDefinition(
        name = "slotParent",
        pattern = Some("Some pattern"),
        range = Some(Reference("base")),
      )

      val slotChild = SlotDefinition(
        name = "slotChild",
        isA = Some(slotParent.reference),
        range = Some(Reference("base")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slotChild.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slotChild.compact, slotParent.compact),
          classes = Map(base.compact),
        ),
      )

      val result = sv.classes("base").derivedAttributes("slotChild").slot
      result.pattern shouldBe Some("Some pattern")
    }

    "inherit zero cardinalities from parent slots" in {
      val slotParent = SlotDefinition(
        name = "slotParent",
        minimumCardinality = Some(0),
        maximumCardinality = Some(0),
        range = Some(Reference("base")),
      )

      val slotChild = SlotDefinition(
        name = "slotChild",
        isA = Some(slotParent.reference),
        range = Some(Reference("base")),
      )

      val base = ClassDefinition(
        name = "base",
        slots = Seq(slotChild.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slotChild.compact, slotParent.compact),
          classes = Map(base.compact),
        ),
      )

      val result = sv.classes("base").derivedAttributes("slotChild").slot
      result.minimumCardinality shouldBe Some(0)
      result.maximumCardinality shouldBe Some(0)
    }

    "not apply slot usages to slots with different ids" in {
      val slot = SlotDefinition(
        name = "slot1",
        title = Some(PlainText("Slot 1")),
        range = Some(Reference("child")),
      )

      val slot2 = SlotDefinition(
        name = "slot2",
        title = Some(PlainText("Slot 2")),
        range = Some(Reference("child")),
      )

      val child = ClassDefinition(
        name = "child",
        slots = Seq(slot.reference, slot2.reference),
        slotUsage = Map(
          SlotDefinition(
            name = "slot1",
            identifier = true,
            range = Some(Reference("child")),
          ).compact,
          SlotDefinition(
            name = "slot2",
            description = Some(PlainText("desc")),
            range = Some(Reference("child")),
          ).compact,
        ),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test/"),
          slotDefinitions = Map(slot.compact, slot2.compact),
          classes = Map(child.compact),
        ),
      )

      val result = sv.classes("child").derivedAttributes("slot1").slot
      result.identifier shouldBe true
      result.description shouldBe None
      result.title shouldBe Some(PlainText("Slot 1"))

      val result2 = sv.classes("child").derivedAttributes("slot2").slot
      result2.identifier shouldBe false
      result2.description shouldBe Some(PlainText("desc"))
      result2.title shouldBe Some(PlainText("Slot 2"))
    }

    "infer the slot's URI" in {
      val slot = SlotDefinition(
        name = "slot1",
        title = Some(PlainText("Slot 1")),
        range = Some(Reference("child")),
      )
      val child = ClassDefinition(
        name = "child",
        slots = Seq(slot.reference),
      )

      val sv = SchemaView.single(
        SchemaDefinition(
          name = "",
          id = Uri("https://neverblink.eu/test"),
          slotDefinitions = Map(slot.compact),
          classes = Map(child.compact),
        ),
      )
      sv.classes("child").derivedAttributes("slot1")
        .uriOrCurie shouldBe UriOrCurie("https://neverblink.eu/test/slot_1")
    }
  }
}
