package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.metamodel.PermissibleValue
import eu.neverblink.linkml.schemaview.*

trait Renames {
  def className(cls: ClassView): String
  def slotName(cls: SlotView): String
  def typeName(cls: TypeView): String
  def enumName(cls: EnumView): String
  def permissibleValueName(ev: EnumView, pv: PermissibleValue): String
}
