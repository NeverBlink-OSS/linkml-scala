package eu.neverblink.linkml.cli

import caseapp.*

object App extends CommandsEntryPoint:
  override def progName: String = "linkml-scala"

  override def commands: Seq[Command[?]] = Seq(
    Validate,
    Shacl,
    JsonSchema,
    Scala,
    Rdfs,
    LinkMl,
  )
