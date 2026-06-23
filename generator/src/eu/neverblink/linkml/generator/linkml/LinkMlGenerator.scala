package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.schemaview.{ClassView, Closure, SchemaView}
import io.circe.Json
import org.virtuslab.yaml.{Node, NodeOps}

class LinkMlGenerator(using sv: SchemaView) {
  import LinkMlGenerator.*

  def materializeClass(classView: ClassView): ClassDefinitionImpl = {
    classView.inner.impl.copy(
      classUri = Some(classView.uriOrCurie),
      isA = None,
      mixins = Seq.empty,
      attributes = classView.derivedAttributes.map((slotKey, slot) => slotKey -> slot.inner.impl),
      slots = Seq.empty,
      slotUsage = Map.empty,
    )
  }

  def reachableFrom(
      cls: ClassDefinition,
      materializeClasses: Boolean,
  ): Set[(ElementTypeTag, Element)] =
    Closure[(ElementTypeTag, Element)](
      (ElementTypeTag.classDef, cls),
      el => {
        val elements: Iterable[Element] = el._2 match {
          case cls: ClassDefinition =>
            if materializeClasses then sv.classes(cls.name).derivedAttributes.map(_._2.slot)
            else
              (cls.slots ++ cls.isA ++ cls.mixins).flatMap(_.resolve)
                ++ cls.attributes.values ++ cls.slotUsage.values
          case typeDefinition: TypeDefinition =>
            (typeDefinition.typeof ++ typeDefinition.unionOf).flatMap(_.resolve)
          case enumDefinition: EnumDefinition =>
            enumDefinition.inherits.flatMap(_.resolve)
          case slotDefinition: SlotDefinition =>
            val inherited = slotDefinition.isA ++ slotDefinition.mixins
            (slotDefinition.range ++ (if !materializeClasses then inherited
                                      else Seq.empty)).flatMap(_.resolve)
          case _ => Seq.empty
        }

        elements.map(el => ElementTypeTag(el) -> el)
      },
      true,
    ).toSet

  def generate(
      treeRootOverride: Option[String] = None,
      materializeClasses: Boolean = true,
  ): SchemaDefinitionImpl = {
    val maybeTreeRoot = sv.treeRootWithOverride(treeRootOverride).get

    val reachableElements = reachableFrom(maybeTreeRoot.get.inner, materializeClasses)

    sv.root.asInstanceOf[SchemaDefinitionImpl].copy(
      imports = Seq.empty,
      classes =
        if materializeClasses then
          sv.classes
            .filter((_, v) => reachableElements.contains(ElementTypeTag.classDef, v.inner))
            .map((k, v) => k -> materializeClass(v))
        else
          sv.classes
            .filter((_, v) => reachableElements.contains(ElementTypeTag.classDef, v.inner))
            .map((k, v) => k -> v.cls.impl)
      ,
      types = sv.types
        .filter((_, v) => reachableElements.contains(ElementTypeTag.typeDef, v.inner))
        .map((k, v) => k -> v.inner.impl.copy(typeUri = Some(v.uriOrCurie))),
      enums = sv.enums
        .filter((_, v) => reachableElements.contains(ElementTypeTag.enumDef, v.inner))
        .map((k, v) => k -> v.inner.impl.copy(enumUri = Some(v.uriOrCurie))),
      slotDefinitions =
        if materializeClasses then Map.empty
        else
          sv.slotDefinitions
            .filter((_, v) => reachableElements.contains(ElementTypeTag.slotDef, v.inner))
            .map((k, v) => k -> v.inner.impl),
    )
  }

  def yamlToJson(yaml: Node): Json = yaml match {
    case Node.MappingNode(entries, _) =>
      val fields = Array.newBuilder[(String, Json)]
      entries.foreach { kv =>
        val value = yamlToJson(kv._2)
        if (value != Json.False) { // skip default false values
          fields.addOne((kv._1.asYaml.trim, value))
        }
      }
      Json.obj(fields.result()*)
    case Node.SequenceNode(elements, _) =>
      Json.arr(elements.map(yamlToJson)*)
    case Node.ScalarNode(value, _) =>
      value match {
        case "true" | "True" | "TRUE" => Json.True
        case "false" | "False" | "FALSE" => Json.False
        case "null" | "~" | "Null" | "NULL" => Json.Null
        case s if s.nonEmpty && {
              val ch = s.charAt(0)
              Character.isDigit(ch) || ch == '-'
            } =>
          yaml.as[BigDecimal] match {
            case Right(v) => Json.fromBigDecimal(v)
            case _ => Json.fromString(value)
          }
        case _ => Json.fromString(value)
      }
    case _ => ???
  }

  def serialize(
      treeRootOverride: Option[String] = None,
      materializeClasses: Boolean = true,
      asJson: Boolean = false,
  ): String = {
    val node = Codec.codec.encode(generate(treeRootOverride, materializeClasses))
    if asJson then yamlToJson(node).spaces2
    else node.asYaml
  }
}

object LinkMlGenerator {
  extension (classDef: ClassDefinition) def impl: ClassDefinitionImpl = classDef.asInstanceOf
  extension (typeDef: TypeDefinition) def impl: TypeDefinitionImpl = typeDef.asInstanceOf
  extension (slotDef: SlotDefinition) def impl: SlotDefinitionImpl = slotDef.asInstanceOf
  extension (enumDef: EnumDefinition) def impl: EnumDefinitionImpl = enumDef.asInstanceOf

  enum ElementTypeTag:
    case classDef, typeDef, slotDef, enumDef, other
  object ElementTypeTag:
    def apply(el: Element): ElementTypeTag = el match {
      case _: ClassDefinition => classDef
      case _: TypeDefinition => typeDef
      case _: SlotDefinition => slotDef
      case _: EnumDefinition => enumDef
      case _ => other
    }
}
