package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, SlotView, TypeView}

trait ScalaRenamer extends Renamer {
  private val scalaKeywords: Set[String] = (
    "abstract case catch class def do else extends final finally for " +
      "forSome if implicit import lazy match new object override package protected return sealed super " +
      "this throw trait try type val var while with yield inline derives end extension using as"
  ).split(' ').toSet

  protected def scalaPascal(baseName: String): String = {
    val name = Case.baseToPascal(baseName)
    if name.isEmpty then "__"
    else if Case.isNumeric(name.head) then "_" + name
    else name
  }

  protected def scalaCamel(baseName: String): String = {
    val name = Case.baseToCamel(baseName)
    if name.isEmpty then "__"
    else if Case.isNumeric(name.head) then "_" + name
    else if scalaKeywords.contains(name) then s"`$name`"
    else name
  }

  override def className(el: ClassView): String = scalaPascal(el.baseName)

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String = slotName(
    el.derivedAttributes(attr.name),
  )

  /** Get the scala `lowerCamelCase` name for a slot, dodge leading digits with an underscore, and
    * quote Scala keywords in backticks.
    */
  override def slotName(el: SlotView): String = scalaCamel(el.baseName)

  override def typeName(el: TypeView): String = scalaPascal(el.baseName)

  override def enumName(el: EnumView): String = scalaPascal(el.baseName)

  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String = scalaPascal(
    Case.base(pv.text),
  )
}

object ScalaRenamer extends ScalaRenamer
