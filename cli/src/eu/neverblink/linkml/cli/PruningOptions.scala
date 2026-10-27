package eu.neverblink.linkml.cli

import caseapp.*
import caseapp.core.Error
import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import eu.neverblink.linkml.generator.util.PruningMode

/** The `--pruning-mode` / `--tree-root` pair, shared by the generate commands that prune unused
  * elements (classes, types, enums) from the schema.
  */
final case class PruningOptions(
    @HelpMessage(PruningOptions.modeHelp + "Default: skip.")
    pruningMode: PruningMode = PruningMode.skip,
    @HelpMessage(PruningOptions.treeRootHelp)
    treeRoot: Option[String] = None,
) {

  /** The pruning mode to hand to a generator, including the tree root override if one was provided.
    */
  def resolvedPruningMode: PruningMode = PruningOptions.resolved(pruningMode, treeRoot)
}

/** The same as [[PruningOptions]], but `schema` by default, for `generate docs`. The documentation
  * of a schema is about the elements it uses, not every type of `linkml:types`.
  */
final case class DocsPruningOptions(
    @HelpMessage(PruningOptions.modeHelp + "Default: schema.")
    pruningMode: PruningMode = PruningMode.schemaRoot,
    @HelpMessage(PruningOptions.treeRootHelp)
    treeRoot: Option[String] = None,
) {

  /** The pruning mode to hand to a generator, including the tree root override if one was provided.
    */
  def resolvedPruningMode: PruningMode = PruningOptions.resolved(pruningMode, treeRoot)
}

object DocsPruningOptions {
  import PruningOptions.parser

  given Parser[DocsPruningOptions] = Parser.derive
  given Help[DocsPruningOptions] = Help.derive
}

object PruningOptions {
  private val names = Seq("treeRoot", "schema", "skip")

  /** The help text of `--pruning-mode`, without its default. */
  final val modeHelp =
    "Which unused elements (classes, types, enums) to remove.\n" +
      "treeRoot - remove all elements unreachable from the tree_root class.\n" +
      "schema - remove all elements unreachable from any of the classes defined in the root schema.\n" +
      "skip - do not remove unused elements.\n"

  /** The help text of `--tree-root`. */
  final val treeRootHelp = "Tree root class name to use instead of the schema-defined tree_root."

  /** [[mode]] with the tree root override [[treeRoot]], if it is the tree root mode. */
  def resolved(mode: PruningMode, treeRoot: Option[String]): PruningMode = mode match {
    case PruningMode.treeRoot(_) => PruningMode.treeRoot(treeRoot)
    case _ => mode
  }

  /** Parses `--pruning-mode`, accepting camel, kebab and snake case alike (`treeRoot`, `tree-root`,
    * `tree_root`).
    */
  given parser: ArgParser[PruningMode] = SimpleArgParser.from(names.mkString("|")) { value =>
    PruningMode.parse(value).toRight(
      Error.MalformedValue("pruning mode", s"$value (expected one of: ${names.mkString(", ")})"),
    )
  }

  given Parser[PruningOptions] = Parser.derive
  given Help[PruningOptions] = Help.derive
}
