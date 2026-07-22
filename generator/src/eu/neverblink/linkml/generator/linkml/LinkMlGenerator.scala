package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.OutputFormat.{json, yaml}
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.PruningMode.skip
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.schemaview.{
  ElementView,
  IncludeAllReachabilityQuery,
  SchemaReachabilityQuery,
  SchemaView,
  TypeView,
}
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
    lazy val defaultRanges = sv.schemas.map(
      _
        .defaultRange
        .getOrElse(Reference[TypeDefinition]("string"))
        .asInstanceOf[Reference[TypeView]],
    ).flatMap(_.resolve)

    lazy val initialSet: Seq[ElementView[?]] = pruningMode match {
      case PruningMode.treeRoot(ovr) =>
        defaultRanges ++ (sv.treeRootWithOverride(ovr).get match {
          case Some(value) => Seq(value)
          case None => sv.root.classes.keys.map(sv.classes.apply)
        })
      case PruningMode.schemaRoot => defaultRanges ++ sv.root.classes.keys.map(sv.classes.apply)
      case PruningMode.skip => Seq.empty
    }

    val query: SchemaReachabilityQuery =
      if pruningMode == skip then IncludeAllReachabilityQuery()
      else if skipClassDerivation then sv.underivedReachabilityQuery(initialSet)
      else sv.derivedReachabilityQuery(initialSet, false)

    sv.root.asInstanceOf[SchemaDefinitionImpl].copy(
      imports = Seq.empty,
      classes = {
        val toInclude = sv.classes.filter((_, v) => query.reachable(v))
        if skipClassDerivation then
          toInclude.map((k, v) =>
            k -> v.cls.impl.copy(
              classUri = Some(v.uriOrCurie),
              fromSchema = Some(v.definingSchema.id),
            ),
          )
        else toInclude.map((k, v) => k -> v.materialize)
      },
      types = sv.types
        .collect {
          case (k, v) if query.reachable(v.inner) =>
            k -> v.inner.impl.copy(
              typeUri = Some(v.uriOrCurie),
              fromSchema = Some(v.definingSchema.id),
            )
        },
      enums = sv.enums
        .collect {
          case (k, v) if query.reachable(v.inner) =>
            k -> v.inner.impl.copy(
              enumUri = Some(v.uriOrCurie),
              fromSchema = Some(v.definingSchema.id),
            )
        },
      slotDefinitions =
        if skipClassDerivation then
          sv.slotDefinitions
            .collect {
              case (k, v) if query.reachable(v.inner) =>
                k -> v.inner.impl.copy(
                  slotUri = Some(v.uriOrCurie),
                  fromSchema = Some(v.definingSchema.id),
                )
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
    if (outputFormat == json) JsonUtil.yamlToJson(node)
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
      * @param _override
      *   If defined, will use the class with the provided name instead of the schema-level
      *   tree_root.
      */
    case treeRoot(_override: Option[String])

    /** Prune all elements that are unreachable from all the classes defined in the root schema. */
    case schemaRoot

    /** Don't prune anything */
    case skip

  /** Serialization format for LinkML models
    */
  enum OutputFormat:
    case yaml, json
}
