package eu.neverblink.linkml.generator.stats

import eu.neverblink.linkml.generator.DocumentGenerator
import eu.neverblink.linkml.generator.stats.model.{PruningMode as _, *}
import eu.neverblink.linkml.generator.util.JsonOutputFormat.yaml
import eu.neverblink.linkml.generator.util.{JsonOutputFormat, JsonUtil, PruningMode}
import eu.neverblink.linkml.metamodel.{CommonMetadata, SlotDefinition}
import eu.neverblink.linkml.runtime.FastUtils.foreachFast
import eu.neverblink.linkml.runtime.{LocalizedText, MultilingualText, PlainText, Reference}
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.yaml.LinkmlYamlCodec

import java.io.OutputStream
import scala.collection.immutable.VectorMap
import scala.collection.mutable

/** Statistics about a schema: how many elements of each kind it has, how many of them are
  * documented, deprecated or mapped, which languages its text is in, and how many schemas it
  * imports. Follows the `schema-stats.yaml` model.
  */
final class StatsGenerator(using sv: SchemaView) extends DocumentGenerator[StatsGenerator.Options] {
  import StatsGenerator.*

  override protected def defaultOptions: Options = Options()

  def generate(options: Options = Options()): SchemaStats = {
    val query = options.pruningMode.derivedQuery(false, true)
    val skip = options.pruningMode == PruningMode.skip
    val root = sv.root
    val languages = mutable.HashMap.empty[String, Int]
    val subsetsUsed = mutable.HashSet.empty[String]

    def count(kind: Kind, cm: CommonMetadata): Unit = {
      kind.add(cm)
      countText(cm, languages)
      cm.inSubset.foreach(s => subsetsUsed.add(s.value))
    }

    val classes = Kind()
    val slots = Kind()
    val attributes = Kind()
    val enums = Kind()
    val permissibleValues = Kind()
    val types = Kind()
    val subsets = Kind()
    val functions = Kind()

    val slotNames = mutable.HashSet.empty[String]
    sv.classes.foreachEntry { (_, cv) =>
      if (!cv.isAny && query.reachable(cv)) {
        count(classes, cv.cls)
        cv.cls.attributes.foreachEntry((_, attribute) => count(attributes, attribute))
        if (!skip) cv.derivedAttributes.foreachEntry { (name, _) =>
          if (
            sv.slotDefinitions.contains(name) &&
            !cv.isSlotFromAttributes(new Reference[SlotDefinition](name))
          ) slotNames.add(name)
        }
      }
    }
    sv.slotDefinitions.foreachEntry { (name, slot) =>
      if (skip || slotNames.contains(name) || query.reachable(slot)) count(slots, slot.slot)
    }
    sv.enums.foreachEntry { (_, ev) =>
      if (query.reachable(ev)) {
        count(enums, ev._enum)
        ev._enum.permissibleValues.foreachEntry { (_, pv) =>
          count(permissibleValues, pv)
          if (pv.meaning.isDefined) permissibleValues.withMeaning += 1
        }
      }
    }
    sv.types.foreachEntry { (_, tv) =>
      if (query.reachable(tv)) count(types, tv._type)
    }
    sv.functions.foreachEntry { (_, fv) =>
      if (query.reachable(fv)) count(functions, fv.function)
    }
    // Pruning follows ranges, which never lead to a subset, so we patch it here
    // TODO: fix pruning of subsets?
    sv.subsets.foreachEntry { (name, sub) =>
      if (skip || root.subsets.contains(name) || subsetsUsed.contains(name))
        count(subsets, sub.subset)
    }
    countText(root, languages)

    SchemaStats(
      elements = ElementStats(
        classes = classes.result(),
        slots = slots.result(),
        attributes = attributes.result(),
        enums = enums.result(),
        permissibleValues = permissibleValues.result(withMeaning = true),
        types = types.result(),
        subsets = subsets.result(),
        functions = functions.result(),
      ),
      imports = ImportStats(direct = root.imports.size, transitive = sv.schemas.size - 1),
      languages = VectorMap.from(
        languages.toSeq.sortBy(_._1).map((tag, n) => tag -> LanguageCount(n, tag)),
      ),
      metadata = MetadataStats(
        hasDescription = root.description.isDefined,
        hasLicense = root.license.isDefined,
        hasTitle = root.title.isDefined,
        hasVersion = root.version.isDefined,
      ),
      pruningMode = options.pruningMode match {
        case PruningMode.treeRoot(_) => model.PruningMode.TreeRoot
        case PruningMode.schemaRoot => model.PruningMode.Schema
        case PruningMode.skip => model.PruningMode.Skip
      },
      schemaId = root.id,
      schemaName = root.name.toString,
    )
  }

  /** The statistics as YAML. */
  override def serialize(options: Options = Options()): String = serialize(options, yaml)

  def serialize(options: Options, format: JsonOutputFormat): String =
    JsonUtil.write(StatsGenerator.codec.encode(generate(options)), format)

  /** Write the statistics as YAML. */
  override def writeTo(out: OutputStream, options: Options = Options()): Unit =
    writeTo(out, options, yaml)

  def writeTo(out: OutputStream, options: Options, format: JsonOutputFormat): Unit =
    JsonUtil.write(StatsGenerator.codec.encode(generate(options)), format, out)
}

object StatsGenerator {

  /** Options for [[StatsGenerator]].
    *
    * @param pruningMode
    *   Which elements are in scope, and so are counted. By default, everything defined in the root
    *   schema and what it uses.
    */
  final case class Options(
      pruningMode: PruningMode = PruningMode.schemaRoot,
  )

  given codec: LinkmlYamlCodec[SchemaStats] = LinkmlYamlCodec.derived

  /** Counts for one kind of element. */
  private final class Kind {
    var count = 0
    var withTitle = 0
    var withDescription = 0
    var deprecated = 0
    var withMappings = 0
    var exact = 0
    var close = 0
    var broad = 0
    var narrow = 0
    var related = 0
    var generic = 0
    var withMeaning = 0

    def add(cm: CommonMetadata): Unit = {
      count += 1
      if (cm.title.isDefined) withTitle += 1
      if (cm.description.isDefined) withDescription += 1
      if (cm.deprecated.isDefined) deprecated += 1
      if (cm.exactMappings.nonEmpty) exact += 1
      if (cm.closeMappings.nonEmpty) close += 1
      if (cm.broadMappings.nonEmpty) broad += 1
      if (cm.narrowMappings.nonEmpty) narrow += 1
      if (cm.relatedMappings.nonEmpty) related += 1
      if (cm.mappings.nonEmpty) generic += 1
      if (
        cm.exactMappings.nonEmpty || cm.closeMappings.nonEmpty || cm.broadMappings.nonEmpty ||
        cm.narrowMappings.nonEmpty || cm.relatedMappings.nonEmpty || cm.mappings.nonEmpty
      ) withMappings += 1
    }

    def result(withMeaning: Boolean = false): ElementKindStats = ElementKindStats(
      count = count,
      deprecated = deprecated,
      mappings = MappingStats(
        broad = broad,
        close = close,
        exact = exact,
        generic = generic,
        narrow = narrow,
        related = related,
      ),
      withDescription = withDescription,
      withMappings = withMappings,
      withMeaning = if withMeaning then Some(this.withMeaning) else None,
      withTitle = withTitle,
    )
  }

  /** Add the strings of [[cm]] to [[counts]], by language. */
  private def countText(cm: CommonMetadata, counts: mutable.HashMap[String, Int]): Unit = {
    def add(text: LocalizedText): Unit = text match {
      case PlainText(_) => counts.updateWith("")(n => Some(n.getOrElse(0) + 1))
      case MultilingualText(mapping) =>
        mapping.foreachEntry((tag, _) => counts.updateWith(tag)(n => Some(n.getOrElse(0) + 1)))
    }
    cm.title.foreachFast(add)
    cm.description.foreachFast(add)
    cm.deprecated.foreachFast(add)
    cm.aliases.foreach(add)
    cm.comments.foreach(add)
    cm.keywords.foreach(add)
    cm.notes.foreach(add)
    cm.altDescriptions.foreachEntry((_, alt) => add(alt.altDescriptionText))
    cm.examples.foreach(_.valueDescription.foreachFast(add))
    cm.structuredAliases.foreach(countText(_, counts))
  }
}
