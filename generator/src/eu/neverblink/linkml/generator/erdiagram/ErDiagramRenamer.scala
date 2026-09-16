package eu.neverblink.linkml.generator.erdiagram

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, SlotView, TypeView}

trait ErDiagramRenamer extends Renamer {

  /** Keep the base form when PascalCase would lose word boundaries. */
  private def pascalOrBase(baseName: String): String = {
    val pascal = Case.baseToPascal(baseName)
    if Case.base(pascal) == baseName then pascal else baseName
  }

  override def className(el: ClassView): String =
    ErName.entity(pascalOrBase(el.baseName))

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
    slotName(el.derivedAttributes(attr.name))

  override def slotName(el: SlotView): String =
    ErName.attributeToken(el.baseName)

  override def typeName(el: TypeView): String =
    ErName.attributeToken(el.baseName)

  override def enumName(el: EnumView): String =
    ErName.attributeToken(pascalOrBase(el.baseName))

  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String =
    ErName.attributeToken(Case.base(pv.text))
}

object ErDiagramRenamer extends ErDiagramRenamer
