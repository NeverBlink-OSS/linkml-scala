package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.generator.options.{PruningMode, PruningOptions}
import eu.neverblink.linkml.metamodel.TypeDefinition
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.schemaview.{
  ElementView,
  IncludeAllReachabilityQuery,
  SchemaReachabilityQuery,
  SchemaView,
  TypeView,
}

/** The method to use for schema definition pruning: tree root-based, schema root based and no
  * pruning
  */
enum Pruning:
  /** Prune all elements that are unreachable from the schema-level tree root class. Falls back to
    * root-schema based pruning if no schema-level tree_root class is present and no override is
    * provided.
    *
    * @param _override
    *   If defined, will use the class with the provided name instead of the schema-level tree_root.
    */
  case treeRoot(_override: Option[String])

  /** Prune all elements that are unreachable from what is defined in the root schema itself: its
    * classes, types, enums, slots and functions.
    */
  case schemaRoot

  /** Don't prune anything */
  case skip

  private def initialSet(using sv: SchemaView): Seq[ElementView[?, ?]] = {
    lazy val defaultRanges = sv.schemas.map(
      _
        .defaultRange
        .getOrElseFast(Reference[TypeDefinition]("string"))
        .asInstanceOf[Reference[TypeView]],
    ).flatMap(_.resolve)

    this match {
      case Pruning.treeRoot(ovr) =>
        sv.treeRootWithOverride(ovr).get
          .foldFast(defaultRanges ++ rootElements) { value => defaultRanges :+ value }
      case Pruning.schemaRoot => defaultRanges ++ rootElements
      case Pruning.skip => Seq.empty
    }
  }

  /** Everything defined in the root schema, as views. */
  private def rootElements(using sv: SchemaView): Seq[ElementView[?, ?]] =
    sv.root.classes.keys.map(sv.classes.apply).toSeq ++
      sv.root.types.keys.map(sv.types.apply) ++
      sv.root.enums.keys.map(sv.enums.apply) ++
      sv.root.slotDefinitions.keys.map(sv.slotDefinitions.apply) ++
      sv.root.functions.keys.map(sv.functions.apply)

  def derivedQuery(inlinedOnly: Boolean, includeClassAncestors: Boolean)(using
      sv: SchemaView,
  ): SchemaReachabilityQuery = {
    if this == skip then IncludeAllReachabilityQuery()
    else sv.derivedReachabilityQuery(initialSet, inlinedOnly, includeClassAncestors)
  }

  def underivedQuery()(using sv: SchemaView): SchemaReachabilityQuery = {
    if this == skip then IncludeAllReachabilityQuery()
    else sv.underivedReachabilityQuery(initialSet)
  }

object Pruning:

  /** The pruning that `mode` names, with the tree root override when it is the tree root mode. */
  def apply(mode: PruningMode, treeRoot: Option[String]): Pruning = mode match {
    case PruningMode.TreeRoot => Pruning.treeRoot(treeRoot)
    case PruningMode.Schema => Pruning.schemaRoot
    case PruningMode.Skip => Pruning.skip
  }

  extension (options: PruningOptions)
    /** The pruning these options ask for. */
    def pruning: Pruning = Pruning(options.pruningMode, options.treeRoot)
