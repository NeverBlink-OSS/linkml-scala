package eu.neverblink.linkml.generator.frictionless

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.runtime.FastUtils.getOrElseFast
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, SlotView, TypeView}

trait FrictionlessRenamer extends Renamer {
  override def className(el: ClassView): String = {
    // Make classes snake_case instead, as frictionless lowercases resource names
    el.cls.alias.getOrElseFast(el.baseName)
  }

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
    attr.alias.getOrElseFast(Case.base(attr.name))

  override def slotName(el: SlotView): String = el.aliasedName

  override def typeName(el: TypeView): String = el.aliasedName

  override def enumName(el: EnumView): String = el.aliasedName

  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String =
    Case.baseToScreamingSnake(Case.base(pv.text))
}

object FrictionlessRenamer extends FrictionlessRenamer
