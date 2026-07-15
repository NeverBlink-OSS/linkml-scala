package eu.neverblink.linkml.cli

import caseapp.*

import java.time.Year

@HelpMessage("Print the version of linkml-scala and its key components.")
final case class VersionOptions()

object Version extends Command[VersionOptions] {
  override def names: List[List[String]] = List(
    List("version"),
    List("v"),
    List("--version"),
  )

  override def run(options: VersionOptions, remainingArgs: RemainingArgs): Unit = {
    printLine(
      s"""
         |linkml-scala   ${BuildInfo.version}
         |-------------------------------------------------------------
         |Scala          ${BuildInfo.scalaVersion}
         |RDF4J          ${BuildInfo.rdf4jVersion}
         |JVM            ${System.getProperty("java.vm.name")} ${System.getProperty(
          "java.vm.version",
        )}
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
