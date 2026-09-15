package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, SlotView, TypeView}

trait ScalaRenamer extends Renamer {

  /** Get the scala `PascalCase` name, dodge leading digits/reserved words with an underscore. No
    * need to check for keywords - all start with lowercase.
    */
  protected def scalaPascal(baseName: String): String = {
    val name = Case.baseToPascal(baseName)
    if name.isEmpty then "__"
    else if Case.isNumeric(name.head) then "_" + name
    else if ScalaWords.reserved.contains(name) then "_" + name
    else name
  }

  /** Get the scala `camelCase` name, dodge leading digits/reserved words with an underscore, and
    * quote Scala keywords in backticks.
    */
  protected def scalaCamel(baseName: String): String = {
    val name = Case.baseToCamel(baseName)
    val len = name.length
    if (len == 0) "__"
    else if (
      Case.isNumeric(name.charAt(0)) ||
      len <= ScalaWords.maxReservedLength && ScalaWords.reserved.contains(name)
    ) "_".concat(name)
    else if (len <= ScalaWords.maxKeywordLength && ScalaWords.keywords.contains(name)) s"`$name`"
    else name
  }

  override def className(el: ClassView): String = scalaPascal(el.baseName)

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String = slotName(
    el.derivedAttributes(attr.name),
  )

  override def slotName(el: SlotView): String = scalaCamel(el.baseName)

  override def typeName(el: TypeView): String = scalaPascal(el.baseName)

  override def enumName(el: EnumView): String = scalaPascal(el.baseName)

  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String = scalaPascal(
    Case.base(pv.text),
  )
}

object ScalaRenamer extends ScalaRenamer
