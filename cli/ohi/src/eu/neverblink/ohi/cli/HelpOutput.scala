package eu.neverblink.ohi.cli

import caseapp.Help
import caseapp.core.Arg
import caseapp.core.help.HelpFormat
import caseapp.core.util.NameOps.toNameOps

private[cli] object HelpOutput {
  private def heading(text: String, colored: Boolean): String =
    CliFormatting.styled(text, "1", colored)

  private def code(text: String, colored: Boolean): String =
    CliFormatting.styled(text, "36", colored)

  private def section(title: String, body: String, colored: Boolean): String =
    s"${heading(title, colored)}\n$body"

  private def commands(lines: Seq[String], colored: Boolean): String =
    lines.map(line => "  " + code(line, colored)).mkString("\n")

  def root(colored: Boolean): String = Seq(
    s"${heading("ohi", colored)} – Find, download and use ontologies.",
    s"${heading("Usage:", colored)} ${code("ohi <command> [options]", colored)}",
    section(
      "Find and download",
      s"  ${code("search", colored)}     Find ontologies by name or topic\n" +
        s"  ${code("inspect", colored)}    Show a release's files and dependencies\n" +
        s"  ${code("get", colored)}        Download an ontology file",
      colored,
    ),
    section(
      "Project",
      s"  ${code("init", colored)}       Create ohi.yaml\n" +
        s"  ${code("add", colored)}        Record a selection and install all locked files\n" +
        s"  ${code("sync", colored)}       Install project ontologies from ohi.lock.json\n" +
        s"  ${code("lock", colored)}       Rebuild ohi.lock.json without downloading",
      colored,
    ),
    section(
      "Local schemas",
      s"  ${code("linkml", colored)}     Validate, create and convert LinkML schemas",
      colored,
    ),
    section("About", s"  ${code("version", colored)}    Print the version of ohi", colored),
    section(
      "Examples",
      commands(Seq("ohi search \"provenance\"", "ohi get w3c/prov-o --format owl-turtle"), colored),
      colored,
    ),
    section(
      "Help",
      commands(Seq("ohi <command> --help"), colored) +
        s"\n  ${code("-h, --help", colored)}    Show this overview",
      colored,
    ),
    s"${heading("Docs:", colored)} ${code("https://ohi.dev/docs/", colored)}",
  ).mkString("\n\n")

  /** Renders ohi command guidance, using parser metadata for the complete option reference. */
  def command(name: String, help: Help[?], full: Boolean, colored: Boolean): String = {
    val purpose = name match {
      case "search" =>
        "Find ontologies by name or topic." + (if full then " Returns one page of matches." else "")
      case "inspect" => "Show one ontology release, its files and dependencies."
      case "get" => "Download an ontology file."
      case "init" =>
        "Create ohi.yaml with a default format, download directory and empty dependency list."
      case "add" =>
        if full then
          "Record the selection in ohi.yaml, rebuild ohi.lock.json and install all locked files."
        else "Record an ontology selection and install all locked files."
      case "sync" =>
        "Install files from ohi.lock.json.\n" +
          "Update the lock only if it is missing or ohi.yaml dependencies have changed."
      case "lock" =>
        "Rebuild ohi.lock.json from ohi.yaml without downloading"
    }
    val usage = name match {
      case "search" => Seq("<query> [options]")
      case "inspect" => Seq("<namespace>/<name>[@version] [options]", "<release-url> [options]")
      case "get" =>
        Seq(
          "<namespace>/<name>[@version] [--format <format>] [options]",
          "<file-url> [options]",
        )
      case "init" => Seq("[directory] [options]")
      case "add" =>
        Seq(
          "<namespace>/<name>[@version] [--format <format>] [options]",
          "<release-url> --artifact <file-url> [options]",
        )
      case "lock" | "sync" => Seq("[options]")
    }
    val input = name match {
      case "inspect" if full =>
        section(
          "Input",
          "  References use ohi.dev. Without @version, selects the latest version.\n" +
            "  A release URL is the metadata URL shown by search. No format is needed.",
          colored,
        )
      case "inspect" => "A release URL is the metadata URL shown by search."
      case "get" | "add" if full =>
        "Without @version, selects the latest version.\n" +
          "Without --format, tries default_format from ohi.yaml, then linkml-yaml.\n" +
          (if name == "get" then
             "Uses download_dir in the current directory's ohi.yaml, otherwise .ohi.\n" +
               "An explicit --to overrides the directory. Hub file URLs must be advertised by their release."
           else "Uses download_dir from ohi.yaml in --project. Release URLs require --artifact.") +
          "\n\n" + CliFormatting.formatsHelp(colored)
      case "sync" if full =>
        "Uses ohi.yaml and ohi.lock.json from the current directory or --project.\n" +
          "With only a lock, downloads into .ohi. Add --init to create ohi.yaml from that lock."
      case "init" =>
        section(
          "Directory",
          "  Defaults to the current directory.\n" +
            "  An explicit directory is created with its parents when needed.\n" +
            "  An existing valid ohi.yaml is kept unchanged.\n" +
            "  --to stores a project-relative download directory. The default is .ohi.\n" +
            "  Creates no lock or download directory.",
          colored,
        )
      case _ => ""
    }
    val examples = name match {
      case "search" =>
        Seq("ohi search \"provenance\"", "ohi search \"provenance\" --page 2 --per-page 10")
      case "inspect" =>
        Seq(
          "ohi inspect w3c/prov-o@2013",
          "ohi inspect https://hub.example/files/packages/w3c/prov-o/2013/",
        )
      case "get" =>
        Seq(
          "ohi get w3c/prov-o --format owl-turtle",
          "ohi get w3c/prov-o@2013 --format owl-turtle --to ./ontologies",
          "ohi get https://hub.example/files/packages/w3c/prov-o/2013/prov-o.ttl --to ./ontologies",
        )
      case "init" => Seq("ohi init", "ohi init ./study --to ontologies")
      case "add" =>
        Seq(
          "ohi add w3c/prov-o --format owl-turtle",
          "ohi add w3c/prov-o@2013 --format owl-turtle --project ./study",
          "ohi add https://hub.example/files/packages/w3c/prov-o/2013/ \\\n    --artifact https://hub.example/files/packages/w3c/prov-o/2013/prov-o.ttl",
        )
      case "lock" => Seq("ohi lock", "ohi lock --project ./study")
      case "sync" => Seq("ohi sync", "ohi sync --init", "ohi sync --project ./study")
    }
    val shownExamples = if full then examples else examples.take(1)
    val exampleBody = commands(shownExamples, colored)
    val next = name match {
      case "init" => section("Next", commands(Seq("ohi add --help"), colored), colored)
      case "lock" =>
        section(
          "Download the recorded files",
          commands(Seq("ohi sync"), colored),
          colored,
        )
      case _ => ""
    }
    val ending = if full then
      Seq(
        options(name, help, colored),
        next,
        section("Docs", "  " + code("https://ohi.dev/docs/", colored), colored),
      )
    else Seq(s"Run 'ohi $name --help' for all options.")
    (Seq(
      s"${heading(s"ohi $name", colored)}\n$purpose",
      section("Usage", commands(usage.map(value => s"ohi $name $value"), colored), colored),
      input,
      section(if shownExamples.size == 1 then "Example" else "Examples", exampleBody, colored),
    ) ++ ending).filter(_.nonEmpty).mkString("\n\n")
  }

  private def options(name: String, help: Help[?], colored: Boolean): String = {
    val order = name match {
      case "search" =>
        Seq(
          "page",
          "perPage",
          "filterBy",
          "sortBy",
          "facetBy",
          "maxFacetValues",
          "allReleases",
          "searchUrl",
          "queryBy",
          "timeoutMs",
          "json",
          "color",
        )
      case "inspect" => Seq("timeoutMs", "json")
      case "get" => Seq("format", "to", "timeoutMs", "json")
      case "init" => Seq("defaultFormat", "to", "json")
      case "add" => Seq("format", "artifact", "project", "json")
      case "lock" => Seq("project", "json")
      case "sync" => Seq("project", "init", "timeoutMs", "json", "color")
    }
    val args = help.args.filterNot(arg => arg.noHelp || arg.group.exists(_.name == "Help"))
      .sortBy(arg => order.indexOf(arg.name.name))
    section(
      "Options",
      (args.map(arg => option(arg, help, colored)) :+
        s"  ${code("-h, --help", colored)}\n    Show complete help").mkString("\n\n"),
      colored,
    )
  }

  private def option(arg: Arg, help: Help[?], colored: Boolean): String = {
    val names = arg.names.distinct.map(_.option(help.nameFormatter)).sortBy(_.length).mkString(", ")
    val value = if arg.isFlag then "" else " " + arg.valueDescription.fold("value")(_.description)
    val description = arg.helpMessage.fold("")(_.message).linesIterator
      .flatMap(line => SearchOutput.wrap(line, 76, "    ", "    ")).mkString("\n")
    s"  ${code(names + value, colored)}\n$description"
  }

  /** Changes only layout and styling of the supplied command's existing help words. */
  def existing(help: Help[?], progName: String, colored: Boolean): String = {
    val format = HelpFormat.default(false)
    val groups = help.args.filterNot(_.noHelp).groupBy(_.group.fold("")(_.name))
    val ordered = groups.keys.toSeq.sorted.filter(_.nonEmpty) ++ groups.keys.filter(_.isEmpty)
    val options = ordered.map { group =>
      val title =
        if group.nonEmpty then s"$group options:"
        else if groups.size > 1 then "Other options:"
        else "Options:"
      section(title, groups(group).map(option(_, help, colored)).mkString("\n\n"), colored)
    }
    (Seq(
      heading(help.copy(progName = progName).usage(format), colored) +
        help.helpMessage.fold("")(message => "\n" + message.message),
    ) ++ options).mkString("\n\n")
  }
}
