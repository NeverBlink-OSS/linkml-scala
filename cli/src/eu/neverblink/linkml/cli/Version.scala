package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.schemaview.buildinfo.CurrentBuild

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

  override def run(options: VersionOptions, remainingArgs: RemainingArgs): Unit =
    options.format.toLowerCase match {
      case "json" => printLine(JsonUtil.yamlToJson(CurrentBuild.node()))
      case "terminal" => printTerminal()
      case other => err(s"Unknown format '$other'. Supported formats: terminal|json.")
    }

  private def printTerminal(): Unit = {
    val info = CurrentBuild.info
    printLine(
      s"""
         |linkml-scala   ${info.linkmlScalaVersion}
         |-------------------------------------------------------------
         |Metamodel      ${info.metamodelVersion}
         |Scala          ${info.scalaVersion}
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
