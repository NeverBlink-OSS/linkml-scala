package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.OutputFormat.{json, yaml}
import eu.neverblink.linkml.generator.util.{JsonUtil, PruningMode}
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.schemaview.SchemaView
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
    val query = if skipClassDerivation then pruningMode.underivedQuery()
    else pruningMode.derivedQuery(false, false)

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

  /** Serialization format for LinkML models
    */
  enum OutputFormat:
    case yaml, json
}
