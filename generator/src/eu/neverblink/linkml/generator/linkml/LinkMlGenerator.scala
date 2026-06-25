package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.OutputFormat.{json, yaml}
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.schemaview.SchemaView.{ElementTypeTag, defaultRangeResolved}
import org.virtuslab.yaml.NodeOps

class LinkMlGenerator(using sv: SchemaView) {
  import LinkMlGenerator.*

  /** Generate a derived [[SchemaDefinition]] based on the provided [[SchemaView]]. Merges imports,
    * runs class derivation and if a `tree_root` class is present, prunes the schema to only include
    * the reachable elements.
    * @param pruningMode
    *   Method to use for schema definition pruning
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
        sv.reachableFrom(Seq(root.inner), skipClassDerivation)
          .incl((ElementTypeTag.typeDef, defaultRange)),
      )

    lazy val elementsFromSchemaRoot: Set[(ElementTypeTag, Element)] =
      sv.reachableFrom(sv.root.classes.values.toSeq, skipClassDerivation)

    def doIncludeElement(element: Element): Boolean =
      pruningMode match {
        case PruningMode.treeRoot(_) =>
          elementsFromTreeRoot match {
            case Some(value) => value.contains(ElementTypeTag(element) -> element)
            case None => elementsFromSchemaRoot.contains(ElementTypeTag(element) -> element)
          }
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
        else toInclude.map((k, v) => k -> v.materialize)
      },
      types = sv.types
        .collect {
          case (k, v) if doIncludeElement(v.inner) =>
            k -> v.inner.impl.copy(typeUri = Some(v.uriOrCurie))
        },
      enums = sv.enums
        .collect {
          case (k, v) if doIncludeElement(v.inner) =>
            k -> v.inner.impl.copy(enumUri = Some(v.uriOrCurie))
        },
      slotDefinitions =
        if skipClassDerivation then
          sv.slotDefinitions
            .collect {
              case (k, v) if doIncludeElement(v.inner) =>
                k -> v.inner.impl.copy(slotUri = Some(v.uriOrCurie))
            }
        else Map.empty,
    )
  }

  /** Generate a derived [[SchemaDefinition]] based on the provided [[SchemaView]] and serialize it
    * as YAML.
    *
    * Merges imports, runs class derivation and if a `tree_root` class is present, prunes the schema
    * to only include the reachable elements.
    * @param pruningMode
    *   Method to use for schema definition pruning
    * @param skipClassDerivation
    *   If true, will not derive classes and instead copy them as-is.
    * @param outputFormat
    *   Output serialization format to use
    * @return
    *   The derived [[SchemaDefinition]]
    */
  def serialize(
      pruningMode: PruningMode = PruningMode.treeRoot(None),
      skipClassDerivation: Boolean = false,
      outputFormat: OutputFormat = yaml,
  ): String = {
    val node = Codec.codec.encode(generate(pruningMode, skipClassDerivation))
    if outputFormat == json then JsonUtil.yamlToJson(node).spaces2
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

  /** The method to use for schema definition pruning: tree root-based, schema root based and no
    * pruning
    */
  enum PruningMode:
    /** Prune all elements that are unreachable from the schema-level tree root class. Falls back to
      * root-schema based pruning if no schema-level tree_root class is present and no override is
      * provided.
      * @param `override`
      *   If defined, will use the class with the provided name instead of the schema-level
      *   tree_root.
      */
    case treeRoot(`override`: Option[String])

    /** Prune all elements that are unreachable from all the classes defined in the root schema. */
    case schemaRoot

    /** Don't prune anything */
    case skip

  /** Serialization format for LinkML models
    */
  enum OutputFormat:
    case yaml, json
}
