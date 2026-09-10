package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, SlotView, TypeView}

/** Ossie naming.
  *
  * Classes, enums, and named types are PascalCase. Relationships use snake_case or the alias, if
  * present.
  */
trait OssieRenamer extends Renamer {
  override def className(el: ClassView): String = el.canonicalName

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String = slotName(
    el.derivedAttributes(attr.name),
  )

  override def slotName(el: SlotView): String = el.aliasedName

  /** The convention for types in Ossie is PascalCase.
    */
  override def typeName(el: TypeView): String = Case.baseToPascal(el.baseName)

  override def enumName(el: EnumView): String = el.canonicalName

  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String = pv.text
}

object OssieRenamer extends OssieRenamer
