package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.generator.options.LinkMlGeneratorOptions
import eu.neverblink.linkml.generator.DocumentGenerator
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.generator.util.Pruning.pruning
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.schemaview.{SchemaCodec, SchemaView}

import java.io.OutputStream

class LinkMlGenerator(using sv: SchemaView) extends DocumentGenerator[LinkMlGeneratorOptions] {

  override protected def defaultOptions: LinkMlGeneratorOptions = LinkMlGeneratorOptions()

  /** Generate a derived [[SchemaDefinition]] based on the provided [[SchemaView]]. Merges imports,
    * derives classes and types and if a `tree_root` class is present, prunes the schema to only
    * include the reachable elements.
    * @param options
    *   What to generate. See [[LinkMlGeneratorOptions]].
    * @return
    *   The derived [[SchemaDefinition]]
    */
  def generate(
      options: LinkMlGeneratorOptions = LinkMlGeneratorOptions(),
  ): SchemaDefinition = {
    import options.skipClassDerivation
    val pruning = options.pruning
    val query =
      if (skipClassDerivation) pruning.underivedQuery()
      else pruning.derivedQuery(false, false)
    sv.root.copy(
      imports = Nil,
      classes =
        if (skipClassDerivation) {
          sv.classes.collect {
            case (k, v) if query.reachable(v.cls) =>
              (
                k,
                v.cls.copy(
                  classUri = new Some(v.uri),
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
            v.derivedType.copy(
              typeUri = new Some(v.uri),
              fromSchema = new Some(v.definingSchema.id),
            ),
          )
      },
      enums = sv.enums.collect {
        case (k, v) if query.reachable(v._enum) =>
          (
            k,
            v._enum.copy(
              enumUri = new Some(v.uri),
              fromSchema = new Some(v.definingSchema.id),
            ),
          )
      },
      functions = sv.functions.collect {
        case (k, v) if query.reachable(v.function) =>
          (k, v.function.copy(fromSchema = new Some(v.definingSchema.id)))
      },
      slotDefinitions =
        if (skipClassDerivation) {
          sv.slotDefinitions.collect {
            case (k, v) if query.reachable(v.slot) =>
              (
                k,
                v.slot.copy(
                  slotUri = new Some(v.uri),
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
    * Merges imports, derives classes and types and if a `tree_root` class is present, prunes the
    * schema to only include the reachable elements.
    * @param options
    *   What to generate. See [[LinkMlGeneratorOptions]].
    * @return
    *   The derived [[SchemaDefinition]]
    */
  override def serialize(
      options: LinkMlGeneratorOptions = LinkMlGeneratorOptions(),
  ): String =
    JsonUtil.write(SchemaCodec.encode(generate(options), options.fullUris), options.format)

  /** Generate a derived [[SchemaDefinition]] and write it to [[out]]. */
  override def writeTo(
      out: OutputStream,
      options: LinkMlGeneratorOptions = LinkMlGeneratorOptions(),
  ): Unit =
    JsonUtil.write(
      SchemaCodec.encode(generate(options), options.fullUris),
      options.format,
      out,
    )
}
