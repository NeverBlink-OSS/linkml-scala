package eu.neverblink.linkml.cli

import caseapp.*
import caseapp.core.Error
import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.schemaview.Case

/** The `--pruning-mode` / `--tree-root` pair, shared by the generate commands that prune unused
  * elements (classes, types, enums) from the schema.
  */
final case class PruningOptions(
    @HelpMessage(
      "Which unused elements (classes, types, enums) to remove.\n" +
        "treeRoot - remove all elements unreachable from the tree_root class.\n" +
        "schema - remove all elements unreachable from any of the classes defined in the root schema.\n" +
        "skip - do not remove unused elements.\n" +
        "Default: treeRoot.",
    )
    pruningMode: PruningMode = PruningMode.treeRoot(None),
    @HelpMessage(
      "Tree root class name to use instead of the schema-defined tree_root.",
    )
    treeRoot: Option[String] = None,
) {

  /** The pruning mode to hand to a generator, including the tree root override if one was provided.
    */
  def resolvedPruningMode: PruningMode = pruningMode match {
    case PruningMode.treeRoot(_) => PruningMode.treeRoot(treeRoot)
    case mode => mode
  }
}

object PruningOptions {
  private val names = Seq("treeRoot", "schema", "skip")

  /** Parses `--pruning-mode`, accepting camel, kebab and snake case alike (`treeRoot`, `tree-root`,
    * `tree_root`).
    */
  given parser: ArgParser[PruningMode] = SimpleArgParser.from(names.mkString("|")) { value =>
    Case.camelCase(value) match {
      case "treeRoot" => Right(PruningMode.treeRoot(None))
      case "schema" => Right(PruningMode.schemaRoot)
      case "skip" => Right(PruningMode.skip)
      case _ =>
        Left(
          Error.MalformedValue(
            "pruning mode",
            s"$value (expected one of: ${names.mkString(", ")})",
          ),
        )
    }
  }

  given Parser[PruningOptions] = Parser.derive
  given Help[PruningOptions] = Help.derive
}
