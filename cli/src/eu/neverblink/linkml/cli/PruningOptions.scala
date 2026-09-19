package eu.neverblink.linkml.cli

import caseapp.*
import caseapp.core.Error
import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import eu.neverblink.linkml.generator.util.PruningMode

/** The `--pruning-mode` / `--tree-root` pair, shared by the generate commands that prune unused
  * elements (classes, types, enums) from the schema.
  */
final case class PruningOptions(
    // BEGIN GENERATED OPTIONS cli.pruning.fields
    @HelpMessage(
      "Which unused elements (classes, types, enums) to remove.\ntreeRoot - remove all elements unreachable from the tree_root class.\nschema - remove all elements unreachable from any of the classes defined in the root schema.\nskip - do not remove unused elements.\nDefault: skip.",
    )
    pruningMode: PruningMode = PruningMode.skip,
    @HelpMessage("Tree root class name to use instead of the schema-defined tree_root.")
    treeRoot: Option[String] = None,
    // END GENERATED OPTIONS cli.pruning.fields
) {

  /** The pruning mode to hand to a generator, including the tree root override if one was provided.
    */
  def resolvedPruningMode: PruningMode = pruningMode match {
    case PruningMode.treeRoot(_) => PruningMode.treeRoot(treeRoot)
    case mode => mode
  }
}

object PruningOptions {
  // BEGIN GENERATED OPTIONS cli.pruning.names
  private val names = Seq("treeRoot", "schema", "skip")
  // END GENERATED OPTIONS cli.pruning.names

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
