package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, SlotView, TypeView}

trait GraphQlRenamer extends Renamer {
  private def graphQlPascal(baseName: String): String = {
    val name = Case.baseToPascal(baseName)
    if name.isEmpty then "_"
    else if Case.isNumeric(name.head) then "_" + name
    else name
  }

  override def className(el: ClassView): String = graphQlPascal(el.baseName)

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
    Case.base(attr.name)

  override def slotName(el: SlotView): String = el.canonicalName

  override def typeName(el: TypeView): String = graphQlPascal(el.baseName)

  override def enumName(el: EnumView): String = graphQlPascal(el.baseName)

  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String =
    Case.baseToScreamingSnake(Case.base(pv.text))

  def permissibleValueName(pv: PermissibleValue): String =
    Case.baseToScreamingSnake(Case.base(pv.text))
}

object GraphQlRenamer extends GraphQlRenamer
