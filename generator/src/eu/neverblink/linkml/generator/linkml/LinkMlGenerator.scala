package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.schemaview.SchemaView.defaultRangeResolved
import eu.neverblink.linkml.schemaview.{ClassView, Closure, SchemaView}
import io.circe.Json
import org.virtuslab.yaml.{Node, NodeOps}

class LinkMlGenerator(using sv: SchemaView) {
  import LinkMlGenerator.*

  /** Materialize the provided [[ClassView]] into a derived [[ClassDefinition]]. This inlines all
    * slots as attributes, and clears any inheritance slots. Additionally, sets the class uri using
    * [[SchemaView]] logic.
    */
  def materializeClass(classView: ClassView): ClassDefinitionImpl = {
    classView.inner.impl.copy(
      classUri = Some(classView.uriOrCurie),
      isA = None,
      mixins = Seq.empty,
      attributes = classView.derivedAttributes.map((slotKey, slot) =>
        slotKey -> slot.inner.impl.copy(
          isA = None,
          mixins = Seq.empty,
        ),
      ),
      slots = Seq.empty,
      slotUsage = Map.empty,
    )
  }

  /** Find all [[Element]]s that are reachable from the [[rootClass]] class.
    *
    * @param fromClasses
    *   Class definition(s) to start the reachability query from.
    * @param skipClassDerivation
    *   If false, will only consider `derivedAttributes` for class derivation. This is in-line with
    *   what [[materializeClass]] will clear. If true, will instead mark inheritance-related slots
    *   as reachable.
    * @todo
    *   Make this search more robust (LNK-110). Currently, this will prune things incorrectly if
    *   there are any boolean slots (like `any_of`)
    * @return
    *   A set of elements reachable from [[rootClass]] and their [[ElementTypeTag]]s
    */
  def reachableFrom(
      fromClasses: Seq[ClassDefinition],
      skipClassDerivation: Boolean,
  ): Set[(ElementTypeTag, Element)] =
    Closure.reflexive[(ElementTypeTag, Element)](
      fromClasses.map(ElementTypeTag.classDef -> _),
      el => {
        val elements: Iterable[Element] = el._2 match {
          case cls: ClassDefinition =>
            if !skipClassDerivation then sv.classes(cls.name).derivedAttributes.map(_._2.slot)
            else
              (cls.slots ++ cls.isA ++ cls.mixins).flatMap(_.resolve)
                ++ cls.attributes.values ++ cls.slotUsage.values
          case typeDefinition: TypeDefinition =>
            (typeDefinition.typeof ++ typeDefinition.unionOf).flatMap(_.resolve)
          case enumDefinition: EnumDefinition =>
            enumDefinition.inherits.flatMap(_.resolve)
          case slotDefinition: SlotDefinition =>
            val inherited = slotDefinition.isA ++ slotDefinition.mixins
            (slotDefinition.range ++ slotDefinition.domain ++ (
              if skipClassDerivation then inherited
              else Seq.empty
            )).flatMap(_.resolve)
          case _ => Seq.empty
        }

        elements.map(el => ElementTypeTag(el) -> el)
      },
    ).toSet

  /** Generate a derived [[SchemaDefinition]] based on the provided [[SchemaView]]. Merges imports,
    * runs class derivation and if a `tree_root` class is present, prunes the schema to only include
    * the reachable elements.
    * @param treeRootOverride
    *   If defined, override the schema `tree_root` class with the one provided
    * @param skipPruning
    *   If true, will not perform pruning of unreachable classes.
    * @param skipClassDerivation
    *   If true, will not derive classes and instead copy them as-is.
    * @return
    *   The derived [[SchemaDefinition]]
    */
  def generate(
      pruningMode: PruningMode = PruningMode.treeRoot(None),
      skipClassDerivation: Boolean = false,
  ): SchemaDefinitionImpl = {
    val defaultRange = sv.root.defaultRangeResolved.resolve.get

    lazy val maybeTreeRoot = pruningMode match {
      case treeRoot: PruningMode.treeRoot => sv.treeRootWithOverride(treeRoot.`override`).get
      case _ => None
    }

    lazy val elementsFromTreeRoot: Option[Set[(ElementTypeTag, Element)]] = maybeTreeRoot
      .map(root =>
        reachableFrom(Seq(root.inner), skipClassDerivation)
          .incl((ElementTypeTag.typeDef, defaultRange)),
      )

    lazy val elementsFromSchemaRoot: Set[(ElementTypeTag, Element)] =
      reachableFrom(sv.root.classes.values.toSeq, skipClassDerivation)

    def doIncludeElement(element: Element): Boolean =
      pruningMode match {
        case PruningMode.treeRoot(_) =>
          elementsFromTreeRoot.forall(_.contains(ElementTypeTag(element) -> element))
        case PruningMode.schemaRoot =>
          elementsFromSchemaRoot.contains(ElementTypeTag(element) -> element)
        case PruningMode.skip => true
      }

    sv.root.asInstanceOf[SchemaDefinitionImpl].copy(
      imports = Seq.empty,
      classes = {
        val toInclude = sv.classes.filter((_, v) => doIncludeElement(v.inner))
        if skipClassDerivation then
          toInclude.map((k, v) => k -> v.cls.impl.copy(classUri = Some(v.uriOrCurie)))
        else toInclude.map((k, v) => k -> materializeClass(v))
      },
      types = sv.types
        .filter((_, v) => doIncludeElement(v.inner))
        .map((k, v) => k -> v.inner.impl.copy(typeUri = Some(v.uriOrCurie))),
      enums = sv.enums
        .filter((_, v) => doIncludeElement(v.inner))
        .map((k, v) => k -> v.inner.impl.copy(enumUri = Some(v.uriOrCurie))),
      slotDefinitions =
        if skipClassDerivation then
          sv.slotDefinitions
            .filter((_, v) => doIncludeElement(v.inner))
            .map((k, v) => k -> v.inner.impl.copy(slotUri = Some(v.uriOrCurie)))
        else Map.empty,
    )
  }

  /** Convert scala-yaml [[Node]] to a circe [[Json]] AST.
    */
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
    case _ => Json.Null
  }

  /** Generate a derived [[SchemaDefinition]] based on the provided [[SchemaView]] and serialize it
    * as YAML.
    *
    * Merges imports, runs class derivation and if a `tree_root` class is present, prunes the schema
    * to only include the reachable elements.
    * @param treeRootOverride
    *   If defined, override the schema `tree_root` class with the one provided
    * @param skipPruning
    *   If true, will not perform pruning of unreachable classes.
    * @param skipClassDerivation
    *   If true, will not derive classes and instead copy them as-is.
    * @param asJson
    *   If true, will instead serialize the derived schema as JSON instead of YAML
    * @return
    *   The derived [[SchemaDefinition]]
    */
  def serialize(
      pruningMode: PruningMode = PruningMode.treeRoot(None),
      skipClassDerivation: Boolean = false,
      asJson: Boolean = false,
  ): String = {
    val node = Codec.codec.encode(generate(pruningMode, skipClassDerivation))
    if asJson then yamlToJson(node).spaces2
    else node.asYaml
  }
}

object LinkMlGenerator {
  // TODO LNK-48: Don't do these horrible casts
  extension (classDef: ClassDefinition)
    private def impl: ClassDefinitionImpl = classDef.asInstanceOf
  extension (typeDef: TypeDefinition) private def impl: TypeDefinitionImpl = typeDef.asInstanceOf
  extension (slotDef: SlotDefinition) private def impl: SlotDefinitionImpl = slotDef.asInstanceOf
  extension (enumDef: EnumDefinition) private def impl: EnumDefinitionImpl = enumDef.asInstanceOf

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

  enum PruningMode:
    case treeRoot(val `override`: Option[String])
    case schemaRoot
    case skip
}
