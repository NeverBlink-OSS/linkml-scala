package eu.neverblink.linkml.generator.erdiagram

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.{Case, ClassView, EnumView, SlotView, TypeView}

trait ErDiagramRenamer extends Renamer {

  /** Any line containing `direction` followed by whitespace and a direction keyword is swallowed
    * whole by Mermaid's lexer and silently reinterpreted as a direction statement - the enclosing
    * quotes do not protect it.
    */
  private val reserved = Set(
    "direction",
    "TB",
    "BT",
    "RL",
    "LR",
  )

  private val keyKeywords = ErKey.values.map(_.toString.toLowerCase).toSet

  /** Mermaid's lexer is case-insensitive, so these cannot be unquoted entity names. `u` is included
    * because `u` directly before a connector lexes as `MD_PARENT`. `end` and `subgraph` are still
    * free in Mermaid 11, but are reserved by its unreleased subgraph support.
    */
  private val reservedEntities =
    Set("one", "many", "to", "class", "classdef", "style", "erdiagram", "u", "end", "subgraph")

  private def prefix(str: String): String =
    if str.isEmpty then "_"
    else if str.head.isDigit || reserved.contains(str) then "_" + str
    else if keyKeywords.contains(str) then str + "_"
    else str

  override def className(el: ClassView): String =
    val str = Case.baseToPascal(el.baseName)
    val lower = str.toLowerCase
    if str.isEmpty then "_"
    else if str.head.isDigit || reservedEntities.contains(lower) then "\"" + str + "\""
    else if reserved.contains(lower) then "_" + str
    else str

  override def classAttributeName(el: ClassView, attr: SlotDefinition): String =
    slotName(el.derivedAttributes(attr.name))

  override def slotName(el: SlotView): String =
    prefix(el.baseName)

  override def typeName(el: TypeView): String =
    prefix(el.baseName)

  override def enumName(el: EnumView): String =
    prefix(Case.baseToPascal(el.baseName))

  override def permissibleValueName(el: EnumView, pv: PermissibleValue): String =
    prefix(Case.base(pv.text))
}

object ErDiagramRenamer extends ErDiagramRenamer
