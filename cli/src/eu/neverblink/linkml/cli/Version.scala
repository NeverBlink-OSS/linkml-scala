package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.schemaview.buildinfo.{BuildInfoImpl, CurrentBuild}

import java.time.Year

@HelpMessage("Print the version of linkml-scala and its key components.")
final case class VersionOptions(
    @HelpMessage(
      "Output format. One of terminal|json. 'terminal' is human-friendly (default); " +
        "'json' is a BuildInfo serialized as JSON.",
    )
    format: String = "terminal",
)

object Version extends BaseCommand[VersionOptions] {
  override def names: List[List[String]] = List(
    List("version"),
    List("v"),
    List("--version"),
  )

  /** What this build is, with the components only the CLI links in filled out. */
  private def buildInfo: BuildInfoImpl =
    CurrentBuild.info.copy(rdf4jVersion = Some(BuildInfo.rdf4jVersion))

  override def run(options: VersionOptions, remainingArgs: RemainingArgs): Unit =
    options.format.toLowerCase match {
      case "json" => printLine(JsonUtil.yamlToJson(CurrentBuild.node(buildInfo)))
      case "terminal" => printTerminal()
      case other => err(s"Unknown format '$other'. Supported formats: terminal|json.")
    }

  private def printTerminal(): Unit = {
    val info = buildInfo
    printLine(
      s"""
         |linkml-scala   ${info.linkmlScalaVersion}
         |-------------------------------------------------------------
         |Metamodel      ${info.metamodelVersion}
         |Scala          ${info.scalaVersion}
         |RDF4J          ${info.rdf4jVersion.getOrElse("-")}
         |Runtime        ${info.runtime.getOrElse("-")}
         |-------------------------------------------------------------""".stripMargin.trim,
    )
    printLine(
      s"""
         |Copyright (C) ${Year.now().getValue} NeverBlink and contributors.
         |Licensed under the Apache License, Version 2.0.
         |For details, see https://www.apache.org/licenses/LICENSE-2.0
         |This software comes with no warranties and is provided 'as-is'.
         |Documentation and author list: https://github.com/NeverBlink-OSS/linkml-scala
         |""".stripMargin,
    )
  }
}
