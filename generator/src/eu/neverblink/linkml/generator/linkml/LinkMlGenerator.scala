package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.generator.DocumentGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.OutputFormat.{json, yaml}
import eu.neverblink.linkml.generator.util.{JsonUtil, PruningMode, Utf8ByteSink}
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.schemaview.SchemaView
import org.virtuslab.yaml.NodeOps

import java.io.OutputStream

class LinkMlGenerator(using sv: SchemaView) extends DocumentGenerator[LinkMlGenerator.Options] {
  import LinkMlGenerator.*

  override protected def defaultOptions: Options = Options()

  /** Generate a derived [[SchemaDefinition]] based on the provided [[SchemaView]]. Merges imports,
    * runs class derivation and if a `tree_root` class is present, prunes the schema to only include
    * the reachable elements.
    * @param options
    *   What to generate. See [[LinkMlGenerator.Options]].
    * @return
    *   The derived [[SchemaDefinition]]
    */
  def generate(
      options: LinkMlGenerator.Options = LinkMlGenerator.Options(),
  ): SchemaDefinitionImpl = {
    import options.{pruningMode, skipClassDerivation}
    val query =
      if (skipClassDerivation) pruningMode.underivedQuery()
      else pruningMode.derivedQuery(false, false)
    sv.root.asInstanceOf[SchemaDefinitionImpl].copy(
      imports = Nil,
      classes =
        if (skipClassDerivation) {
          sv.classes.collect {
            case (k, v) if query.reachable(v.cls) =>
              (
                k,
                v.cls.impl.copy(
                  classUri = new Some(v.uriOrCurie),
                  fromSchema = new Some(v.definingSchema.id),
                ),
              )
          }
        } else {
          sv.classes.collect {
            case (k, v) if query.reachable(v.cls) =>
              (k, v.materialize)
          }
        },
      types = sv.types.collect {
        case (k, v) if query.reachable(v._type) =>
          (
            k,
            v._type.impl.copy(
              typeUri = new Some(v.uriOrCurie),
              fromSchema = new Some(v.definingSchema.id),
            ),
          )
      },
      enums = sv.enums.collect {
        case (k, v) if query.reachable(v._enum) =>
          (
            k,
            v._enum.impl.copy(
              enumUri = new Some(v.uriOrCurie),
              fromSchema = new Some(v.definingSchema.id),
            ),
          )
      },
      slotDefinitions =
        if (skipClassDerivation) {
          sv.slotDefinitions.collect {
            case (k, v) if query.reachable(v.slot) =>
              (
                k,
                v.slot.impl.copy(
                  slotUri = new Some(v.uriOrCurie),
                  fromSchema = new Some(v.definingSchema.id),
                ),
              )
          }
        } else Map.empty,
    )
  }

  /** Generate a derived [[SchemaDefinition]] based on the provided [[SchemaView]] and serialize it
    * as YAML.
    *
    * Merges imports, runs class derivation and if a `tree_root` class is present, prunes the schema
    * to only include the reachable elements.
    * @param options
    *   What to generate. See [[LinkMlGenerator.Options]].
    * @return
    *   The derived [[SchemaDefinition]]
    */
  override def serialize(
      options: LinkMlGenerator.Options = LinkMlGenerator.Options(),
  ): String = {
    val node = Codec.codec.encode(generate(options))
    if (options.outputFormat == json) JsonUtil.yamlToJson(node)
    else node.asYaml
  }

  /** Generate a derived [[SchemaDefinition]] and write it to [[out]].
    *
    * JSON goes straight out through jsoniter. YAML still builds the whole document as a string
    * first, because scala-yaml's writer has no streaming form. This could be improved in the future
    * to reduce peak memory usage.
    */
  override def writeTo(
      out: OutputStream,
      options: LinkMlGenerator.Options = LinkMlGenerator.Options(),
  ): Unit = {
    val node = Codec.codec.encode(generate(options))
    if (options.outputFormat == json) JsonUtil.writeJson(node, out)
    else {
      val sink = new Utf8ByteSink(out)
      sink.append(node.asYaml)
      sink.flush()
    }
  }
}

object LinkMlGenerator {

  /** Options for [[LinkMlGenerator]].
    *
    * @param pruningMode
    *   Method to use for schema definition pruning.
    * @param skipClassDerivation
    *   If true, will not derive classes and instead copy them as-is.
    * @param outputFormat
    *   Output serialization format to use.
    */
  final case class Options(
      pruningMode: PruningMode = PruningMode.skip,
      skipClassDerivation: Boolean = false,
      outputFormat: OutputFormat = yaml,
  )

  // TODO LNK-48: Don't do these horrible casts
  extension (inline classDef: ClassDefinition)
    private inline def impl: ClassDefinitionImpl = classDef.asInstanceOf
  extension (inline typeDef: TypeDefinition)
    private inline def impl: TypeDefinitionImpl = typeDef.asInstanceOf
  extension (inline slotDef: SlotDefinition)
    private inline def impl: SlotDefinitionImpl = slotDef.asInstanceOf
  extension (inline enumDef: EnumDefinition)
    private inline def impl: EnumDefinitionImpl = enumDef.asInstanceOf

  /** Serialization format for LinkML models
    */
  enum OutputFormat:
    case yaml, json
}
