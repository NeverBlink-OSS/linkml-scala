package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** A constraint can narrow the values that a type, a union or a parent allows, but not widen them.
  * So a value of a slot is also a valid value of its range type, of each union member it matches,
  * and of the slot in each parent class, mixin and parent slot.
  */
class ConstraintNarrowingSpec extends AnyWordSpec, Matchers {
  private def load(yaml: String): SchemaView =
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))

  private def attribute(view: SchemaView, cls: String, slot: String): TypeAttributeView =
    view.classes(cls).attributeViews(slot).asInstanceOf[TypeAttributeView]

  "Value bounds" should {
    val view = load(
      """id: https://example.org/bounds
        |name: bounds
        |imports: [linkml:types]
        |types:
        |  Small:
        |    typeof: integer
        |    minimum_value: 0
        |    maximum_value: 10
        |  Bigger:
        |    typeof: Small
        |    minimum_value: -100
        |    maximum_value: 100
        |  SmallOrText:
        |    union_of: [Small, string]
        |    maximum_value: 100
        |  Pair:
        |    union_of: [Small, string]
        |  OuterUnion:
        |    union_of: [Pair]
        |    maximum_value: 100
        |slots:
        |  parent_slot: {range: integer, maximum_value: 10}
        |  child_slot: {is_a: parent_slot, maximum_value: 100}
        |classes:
        |  Item:
        |    slots: [child_slot]
        |    attributes:
        |      child_type: {range: Bigger}
        |      slot_over_type: {range: Small, minimum_value: -100, maximum_value: 100}
        |      union_type: {range: SmallOrText}
        |      nested_union: {range: OuterUnion}
        |      slot_over_union: {range: Pair, maximum_value: 100}
        |      inherited: {range: integer, minimum_value: 0, maximum_value: 10}
        |  Widening:
        |    mixin: true
        |    slot_usage:
        |      inherited: {maximum_value: 100}
        |  SubItem:
        |    is_a: Item
        |    slot_usage:
        |      inherited: {minimum_value: -100, maximum_value: 100}
        |  MixedItem:
        |    is_a: Item
        |    mixins: [Widening]
        |""".stripMargin,
    )

    def bounds(alternatives: Seq[ValueAlternative]): Seq[(String, Option[String], Option[String])] =
      alternatives.map(a =>
        (a.typeView.name, a.minimumValue.map(_.value.trim), a.maximumValue.map(_.value.trim)),
      )

    "not let a child type widen the bounds of its parent" in {
      val bigger = view.types("Bigger")
      bigger.derivedType.minimumValue.map(_.value.trim) shouldBe Some("0")
      bigger.derivedType.maximumValue.map(_.value.trim) shouldBe Some("10")
      bounds(attribute(view, "Item", "child_type").alternatives) shouldBe
        Seq(("Bigger", Some("0"), Some("10")))
    }

    "not let a slot widen the bounds of its range type" in {
      val slot = attribute(view, "Item", "slot_over_type")
      slot.minimumValue.map(_.value.trim) shouldBe Some("0")
      slot.maximumValue.map(_.value.trim) shouldBe Some("10")
      bounds(slot.alternatives) shouldBe Seq(("Small", Some("0"), Some("10")))
    }

    "not let a union widen the bounds of its members" in {
      bounds(attribute(view, "Item", "union_type").alternatives) shouldBe
        Seq(("Small", Some("0"), Some("10")), ("string", None, None))
      bounds(attribute(view, "Item", "nested_union").alternatives) shouldBe
        Seq(("Small", Some("0"), Some("10")), ("string", None, None))
    }

    "not let a slot widen the bounds of the members of its union range" in {
      bounds(attribute(view, "Item", "slot_over_union").alternatives) shouldBe
        Seq(("Small", Some("0"), Some("10")), ("string", None, None))
    }

    "not let a slot_usage in a subclass or a mixin widen the bounds of the parent class" in {
      for cls <- Seq("SubItem", "MixedItem") do {
        val slot = view.classes(cls).derivedAttributes("inherited").slot
        slot.minimumValue.map(_.value.trim) shouldBe Some("0")
        slot.maximumValue.map(_.value.trim) shouldBe Some("10")
      }
    }

    "not let a child slot widen the bounds of its parent slot" in {
      val slot = view.classes("Item").derivedAttributes("child_slot").slot
      slot.maximumValue.map(_.value.trim) shouldBe Some("10")
    }

    "keep a narrower bound from any place" in {
      val narrow = load(
        """id: https://example.org/narrow
          |name: narrow
          |imports: [linkml:types]
          |types:
          |  Small: {typeof: integer, maximum_value: 10}
          |  Smaller: {typeof: Small, maximum_value: 5}
          |  SmallOrText:
          |    union_of: [Small, string]
          |    maximum_value: 5
          |classes:
          |  Item:
          |    attributes:
          |      child_type: {range: Smaller}
          |      slot_over_type: {range: Small, maximum_value: 3}
          |      union_type: {range: SmallOrText}
          |""".stripMargin,
      )
      bounds(attribute(narrow, "Item", "child_type").alternatives) shouldBe
        Seq(("Smaller", None, Some("5")))
      bounds(attribute(narrow, "Item", "slot_over_type").alternatives) shouldBe
        Seq(("Small", None, Some("3")))
      bounds(attribute(narrow, "Item", "union_type").alternatives) shouldBe
        Seq(("Small", None, Some("5")), ("string", None, None))
    }
  }

  "Lengths" should {
    val view = load(
      """id: https://example.org/lengths
        |name: lengths
        |imports: [linkml:types]
        |types:
        |  Short:
        |    typeof: string
        |    minimum_length: 2
        |    maximum_length: 3
        |  Longer:
        |    typeof: Short
        |    minimum_length: 1
        |    maximum_length: 10
        |  ShortOrNumber:
        |    union_of: [Short, integer]
        |    maximum_length: 10
        |  Pair:
        |    union_of: [Short, integer]
        |  OuterUnion:
        |    union_of: [Pair]
        |    maximum_length: 10
        |slots:
        |  parent_slot: {range: string, minimum_length: 2, maximum_length: 3}
        |  child_slot: {is_a: parent_slot, maximum_length: 10}
        |  plain_child_slot: {is_a: parent_slot}
        |classes:
        |  Item:
        |    slots: [child_slot, plain_child_slot]
        |    attributes:
        |      child_type: {range: Longer}
        |      slot_over_type: {range: Short, minimum_length: 1, maximum_length: 10}
        |      union_type: {range: ShortOrNumber}
        |      nested_union: {range: OuterUnion}
        |      slot_over_union: {range: Pair, maximum_length: 10}
        |      inherited: {range: string, minimum_length: 2, maximum_length: 3}
        |  Widening:
        |    mixin: true
        |    slot_usage:
        |      inherited: {maximum_length: 10}
        |  SubItem:
        |    is_a: Item
        |    slot_usage:
        |      inherited: {minimum_length: 1, maximum_length: 10}
        |  MixedItem:
        |    is_a: Item
        |    mixins: [Widening]
        |""".stripMargin,
    )

    def lengths(alternatives: Seq[ValueAlternative]): Seq[(String, Option[Int], Option[Int])] =
      alternatives.map(a => (a.typeView.name, a.minimumLength, a.maximumLength))

    "not let a child type widen the lengths of its parent" in {
      val longer = view.types("Longer")
      longer.derivedType.minimumLength shouldBe Some(2)
      longer.derivedType.maximumLength shouldBe Some(3)
      lengths(attribute(view, "Item", "child_type").alternatives) shouldBe
        Seq(("Longer", Some(2), Some(3)))
    }

    "not let a slot widen the lengths of its range type" in {
      val slot = attribute(view, "Item", "slot_over_type")
      slot.minimumLength shouldBe Some(2)
      slot.maximumLength shouldBe Some(3)
      lengths(slot.alternatives) shouldBe Seq(("Short", Some(2), Some(3)))
    }

    "not let a union widen the lengths of its members" in {
      lengths(attribute(view, "Item", "union_type").alternatives) shouldBe
        Seq(("Short", Some(2), Some(3)), ("integer", None, None))
      lengths(attribute(view, "Item", "nested_union").alternatives) shouldBe
        Seq(("Short", Some(2), Some(3)), ("integer", None, None))
    }

    "not let a slot widen the lengths of the members of its union range" in {
      lengths(attribute(view, "Item", "slot_over_union").alternatives) shouldBe
        Seq(("Short", Some(2), Some(3)), ("integer", None, None))
    }

    "not let a slot_usage in a subclass or a mixin widen the lengths of the parent class" in {
      for cls <- Seq("SubItem", "MixedItem") do {
        val slot = view.classes(cls).derivedAttributes("inherited").slot
        slot.minimumLength shouldBe Some(2)
        slot.maximumLength shouldBe Some(3)
      }
    }

    "not let a child slot widen or drop the lengths of its parent slot" in {
      for name <- Seq("child_slot", "plain_child_slot") do {
        val slot = view.classes("Item").derivedAttributes(name).slot
        slot.minimumLength shouldBe Some(2)
        slot.maximumLength shouldBe Some(3)
      }
    }

    "keep a narrower length from any place" in {
      val narrow = load(
        """id: https://example.org/narrow
          |name: narrow
          |imports: [linkml:types]
          |types:
          |  Short: {typeof: string, maximum_length: 3}
          |  Shorter: {typeof: Short, maximum_length: 2}
          |classes:
          |  Item:
          |    attributes:
          |      child_type: {range: Shorter}
          |      slot_over_type: {range: Short, maximum_length: 1}
          |""".stripMargin,
      )
      lengths(attribute(narrow, "Item", "child_type").alternatives) shouldBe
        Seq(("Shorter", None, Some(2)))
      lengths(attribute(narrow, "Item", "slot_over_type").alternatives) shouldBe
        Seq(("Short", None, Some(1)))
    }
  }
}
