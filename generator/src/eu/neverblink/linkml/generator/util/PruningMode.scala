package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.metamodel.TypeDefinition
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.schemaview.{
  Case,
  ElementView,
  IncludeAllReachabilityQuery,
  SchemaReachabilityQuery,
  SchemaView,
  TypeView,
}

/** The method to use for schema definition pruning: tree root-based, schema root based and no
  * pruning
  */
enum PruningMode:
  /** Prune all elements that are unreachable from the schema-level tree root class. Falls back to
    * root-schema based pruning if no schema-level tree_root class is present and no override is
    * provided.
    *
    * @param _override
    *   If defined, will use the class with the provided name instead of the schema-level tree_root.
    */
  case treeRoot(_override: Option[String])

  /** Prune all elements that are unreachable from all the classes defined in the root schema. */
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
      case PruningMode.treeRoot(ovr) =>
        sv.treeRootWithOverride(ovr).get
          .foldFast(defaultRanges ++ sv.root.classes.keys.map(sv.classes.apply)) { value =>
            defaultRanges :+ value
          }
      case PruningMode.schemaRoot => defaultRanges ++ sv.root.classes.keys.map(sv.classes.apply)
      case PruningMode.skip => Seq.empty
    }
  }

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

object PruningMode:

  /** The mode the caller named, or None if it is not one we know.
    *
    * Accepts camel, kebab and snake case (`treeRoot`, `tree-root`, `tree_root`).
    */
  def parse(mode: String): Option[PruningMode] = Case.base(mode) match {
    case "tree_root" => Some(PruningMode.treeRoot(None))
    case "schema" => Some(PruningMode.schemaRoot)
    case "skip" => Some(PruningMode.skip)
    case _ => None
  }

  def unknownMode(mode: String): String =
    s"Unknown pruning mode '$mode'. Supported modes: treeRoot, schema, skip."

  /** The named mode, with treeRootOverride applied when it is the tree-root one.
    *
    * @throws IllegalArgumentException
    *   if `mode` is not a mode we know
    */
  def apply(mode: String, treeRootOverride: Option[String]): PruningMode =
    parse(mode) match {
      case Some(PruningMode.treeRoot(_)) => PruningMode.treeRoot(treeRootOverride)
      case Some(other) => other
      case None => throw IllegalArgumentException(unknownMode(mode))
    }
