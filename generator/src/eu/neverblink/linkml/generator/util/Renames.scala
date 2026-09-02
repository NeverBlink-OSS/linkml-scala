package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.metamodel.PermissibleValue
import eu.neverblink.linkml.schemaview.*

trait Renames {
  def className(el: ClassView): String
  def slotName(el: SlotView): String
  def typeName(el: TypeView): String
  def enumName(el: EnumView): String
  def permissibleValueName(el: EnumView, pv: PermissibleValue): String
}
