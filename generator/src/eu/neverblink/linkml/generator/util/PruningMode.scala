package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.metamodel.TypeDefinition
import eu.neverblink.linkml.runtime.Reference
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

  private def initialSet(using sv: SchemaView): Seq[ElementView[?]] = {
    lazy val defaultRanges = sv.schemas.map(
      _
        .defaultRange
        .getOrElse(Reference[TypeDefinition]("string"))
        .asInstanceOf[Reference[TypeView]],
    ).flatMap(_.resolve)

    this match {
      case PruningMode.treeRoot(ovr) =>
        defaultRanges ++ (sv.treeRootWithOverride(ovr).get match {
          case Some(value) => Seq(value)
          case None => sv.root.classes.keys.map(sv.classes.apply)
        })
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
  def apply(mode: String, treeRootOverride: Option[String]): PruningMode =
    Case.camelCase(mode) match {
      case "treeRoot" => PruningMode.treeRoot(treeRootOverride)
      case "schema" => PruningMode.schemaRoot
      case "skip" => PruningMode.skip
    }
