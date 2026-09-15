package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, SlotView, TypeView}

trait GraphQlRenamer extends Renamer {

  /** Name fixes for GraphQL: no leading digits, no empty names
    * @note
    *   Empty base names are a LinkML error, but this prevents a generator throw
    */
  private def fix(value: String): String = {
    if value.isEmpty then "_"
    else if Case.isNumeric(value.head) then "_" + value
    else value
  }

  private def graphQlPascal(baseName: String): String = fix(Case.baseToPascal(baseName))

  override def className(el: ClassView): String = graphQlPascal(el.baseName)

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
    Case.base(attr.name)

  override def slotName(el: SlotView): String = fix(el.canonicalName)

  override def typeName(el: TypeView): String = graphQlPascal(el.baseName)

  override def enumName(el: EnumView): String = graphQlPascal(el.baseName)

  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String =
    permissibleValueName(pv)

  def permissibleValueName(pv: PermissibleValue): String =
    fix(Case.baseToScreamingSnake(Case.base(pv.text)))
}

object GraphQlRenamer extends GraphQlRenamer
