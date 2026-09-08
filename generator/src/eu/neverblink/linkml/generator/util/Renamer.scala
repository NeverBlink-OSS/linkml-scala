package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.*

/** Mixin to provide renaming mappings to a generator. These mappings should be bijective with the [[Case.base]] form of names.
 * Used by [[TranslationGenerator]] to generate mappings from a LinkML name to
  */
trait Renamer {
  def className(el: ClassView): String
  def classAttributeName(el: ClassView, attr: SlotDefinition): String
  def slotName(el: SlotView): String
  def typeName(el: TypeView): String
  def enumName(el: EnumView): String
  def permissibleValueName(el: EnumView, pv: PermissibleValue): String
}
