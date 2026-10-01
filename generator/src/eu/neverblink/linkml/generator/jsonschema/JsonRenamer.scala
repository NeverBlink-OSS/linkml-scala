package eu.neverblink.linkml.generator.jsonschema

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.runtime.FastUtils.getOrElseFast
import eu.neverblink.linkml.schemaview.{ClassView, EnumView, SlotView, TypeView}

/** Names in the JSON form of LinkML data, and in the JSON Schema that describes it. */
trait JsonRenamer extends Renamer {

  /** The class's key in the JSON Schema `$defs`. Not part of the data. */
  override def className(el: ClassView): String = el.cls.alias.getOrElseFast(el.canonicalName)

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
    slotName(el.derivedAttributes(attr.name))

  /** The slot's key in JSON objects. */
  override def slotName(el: SlotView): String = el.slot.alias.getOrElseFast(el.baseName)

  /** Types are inlined in the JSON Schema, so this name appears nowhere. */
  override def typeName(el: TypeView): String = el.name

  /** The enum's key in the JSON Schema `$defs`. Not part of the data. */
  override def enumName(el: EnumView): String = el.name

  /** The value as it appears in the data. */
  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String = pv.text
}

object JsonRenamer extends JsonRenamer
