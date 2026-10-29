package eu.neverblink.ohi.cli

import caseapp.*
import eu.neverblink.linkml.runtime.{Uri, stringify}
import eu.neverblink.ohi.catalog.{CatalogClient, CatalogException}
import eu.neverblink.ohi.model.{CatalogErrorJson, LockException, LockFiles}
import eu.neverblink.ohi.model.generated.config.CatalogConfig
import eu.neverblink.ohi.model.generated.error.{CatalogError, CatalogErrorCode, CatalogOperation}
import eu.neverblink.ohi.model.generated.lockerror.{LockError, LockErrorCode, LockOperation}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest

import java.nio.file.{InvalidPathException, Path}

final case class CatalogOptions(
    @HelpMessage("Write JSON output")
    json: Boolean = false,
    @HelpMessage("Request timeout in milliseconds (Default: 30000)")
    @ValueDescription("<number>")
    timeoutMs: Option[Int] = None,
)

object CatalogOptions {
  given Parser[CatalogOptions] = Parser.derive
  given Help[CatalogOptions] = Help.derive
}

trait HasCatalogOptions {
  @Recurse
  val common: CatalogOptions
}

/** Runs a one-argument catalog command, printing its result to stdout. Reports catalog and delivery
  * failures to stderr with exit status 1.
  */
abstract class CatalogCommand[T <: HasCatalogOptions: {Parser, Help}] extends OhiCommand[T] {
  protected def commandName: String
  protected def argumentName: String

  override final def names: List[List[String]] = List(List(commandName))

  protected def config(options: T): CatalogConfig =
    CatalogConfig(timeoutMs = options.common.timeoutMs)

  protected def execute(options: T, input: String, client: CatalogClient): String

  // Explain validated input failures here, keeping the SDK as the validation authority.
  protected def inputCorrection(options: T, input: String, error: CatalogError): Unit =
    if options.common.timeoutMs.exists(_ < 1) then
      correction(
        s"--timeout-ms must be at least 1. Received ${options.common.timeoutMs.get}.",
        "Use a positive timeout in milliseconds.",
      )

  override def run(options: T, remainingArgs: RemainingArgs): Unit = {
    val inputs = remainingArgs.all
    if inputs.isEmpty then shortHelp()
    if inputs.size != 1 then
      correction(
        if commandName == "search" then
          s"Search accepts one query. Received ${inputs.size} arguments: ${inputs.mkString(", ")}."
        else
          s"$commandName accepts one $argumentName. Received ${inputs.size} arguments: ${inputs.mkString(", ")}."
        ,
        if commandName == "search" then
          "To search for these words together:\n  " + example(
            invocation(inputs = Some(Seq(inputs.mkString(" ")))),
          )
        else "",
      )
    try printLine(execute(options, inputs.head, new CatalogClient(config(options))))
    catch {
      case failure: CatalogException =>
        val error = failure.error
        if !options.common.json && error.code == CatalogErrorCode.InvalidInput then
          inputCorrection(options, inputs.head, error)
        err(
          if options.common.json then CatalogErrorJson.encode(error)
          else {
            val (diagnostic, recovery) = ErrorOutput.catalog(
              stringify(error.code),
              stringify(error.operation),
              error.message,
              error.url,
              error.status,
              error.responseJson,
              timeoutOption = true,
              colored = errorColored,
            )
            s"$diagnostic\n\n$recovery"
          },
        )
      case failure: CliFileException =>
        err(
          if options.common.json then CatalogOutput.fileError(failure.error)
          else {
            val error = failure.error
            val (diagnostic, recovery) = ErrorOutput.file(
              stringify(error.code),
              error.message,
              error.url,
              error.directory,
              error.path,
              error.partialPath,
              errorColored,
            )
            s"$diagnostic\n\n$recovery"
          },
        )
    }
  }
}

@HelpMessage("Find ontologies by name or topic")
@ArgsName("<query>")
final case class SearchOptions(
    @Recurse
    common: CatalogOptions,
    @HelpMessage("Color human output. Default: auto")
    @ValueDescription("<auto|never>")
    color: CliColor = CliColor.Auto,
    @HelpMessage("Complete HTTP search endpoint")
    @ValueDescription("<url>")
    searchUrl: Option[String] = None,
    @HelpMessage(
      "Search field names, separated by commas\nSupply both --search-url and --query-by,\nor omit both to search ohi.dev",
    )
    @ValueDescription("<fields>")
    queryBy: Option[String] = None,
    @HelpMessage("Page number, starting at 1. Default: 1")
    @ValueDescription("<number>")
    page: Option[Int] = None,
    @HelpMessage("Matches per page, from 1 to 250. Default: 10")
    @ValueDescription("<number>")
    perPage: Option[Int] = None,
    @HelpMessage("Filter matches, for example namespace:=w3c")
    @ValueDescription("<expression>")
    filterBy: Option[String] = None,
    @HelpMessage("Order matches, for example published_at:desc")
    @ValueDescription("<expression>")
    sortBy: Option[String] = None,
    @HelpMessage("Count values by comma-separated fields\nFor example namespace")
    @ValueDescription("<expression>")
    facetBy: Option[String] = None,
    @HelpMessage("Maximum values per facet, at least 1\nOmit to use the search service default")
    @ValueDescription("<count>")
    maxFacetValues: Option[Int] = None,
) extends HasCatalogOptions

object Search extends CatalogCommand[SearchOptions] {
  override protected def commandName: String = "search"
  override protected def argumentName: String = "query"

  override protected def inputCorrection(
      options: SearchOptions,
      input: String,
      error: CatalogError,
  ): Unit = {
    super.inputCorrection(options, input, error)
    if error.operation == CatalogOperation.Configure then {
      if options.queryBy.exists(_.isBlank) then correction("--query-by cannot be empty.")
      if options.searchUrl.nonEmpty && options.queryBy.isEmpty then
        correction(
          "Supply --query-by with --search-url.",
          s"Supply the indexed fields for ${options.searchUrl.get}.\nExample, if this catalog indexes title and name:\n  " +
            example(invocation(Seq("--query-by" -> Some("title,name")))),
          invalid = false,
        )
      if options.queryBy.nonEmpty && options.searchUrl.isEmpty then
        correction(
          "Supply --search-url with --query-by.",
          "Supply the complete HTTP search endpoint for this catalog.",
          invalid = false,
        )
    } else {
      if input.isBlank then
        correction(
          "The search query is empty.",
          "Enter search text, or use '*' to browse.\n  " + example(
            invocation(inputs = Some(Seq("*"))),
          ),
        )
      val ranges = Seq(
        ("--page", options.page, 1, Int.MaxValue),
        ("--per-page", options.perPage, 1, 250),
        ("--max-facet-values", options.maxFacetValues, 1, Int.MaxValue),
      )
      ranges.foreach { (name, value, minimum, maximum) =>
        value.filter(v => v < minimum || v > maximum).foreach { received =>
          val range =
            if maximum == Int.MaxValue then s"at least $minimum" else s"from $minimum to $maximum"
          correction(
            s"$name must be $range. Received $received.",
            (if name == "--page" then "To request the first page:"
             else "Example with an accepted value:") +
              "\n  " + example(invocation(Seq(name -> Some(minimum.toString)))),
          )
        }
      }
      Seq(
        "--filter-by" -> options.filterBy,
        "--sort-by" -> options.sortBy,
        "--facet-by" -> options.facetBy,
      )
        .foreach { (name, value) =>
          if value.exists(_.isBlank) then correction(s"$name cannot be empty.")
        }
    }
  }

  override protected def config(options: SearchOptions): CatalogConfig =
    CatalogConfig(
      searchUrl = options.searchUrl.map(Uri(_)),
      queryBy = options.queryBy,
      timeoutMs = options.common.timeoutMs,
    )

  override protected def execute(
      options: SearchOptions,
      input: String,
      client: CatalogClient,
  ): String = {
    val environment = sys.env
    val colored = !options.common.json && options.color.enabled(
      terminal = (outStream eq System.out) && CliColor.terminalAttached,
      term = environment.get("TERM"),
    )
    CatalogOutput.search(
      client.search(
        SearchRequest(
          q = input,
          page = options.page,
          perPage = options.perPage,
          filterBy = options.filterBy,
          sortBy = options.sortBy,
          facetBy = options.facetBy,
          maxFacetValues = options.maxFacetValues,
        ),
      ),
      options.common.json,
      SearchOutput.width(environment.get("COLUMNS")),
      colored,
    )
  }
}

@HelpMessage("Show a release's files and dependencies")
@ArgsName("<release-url>")
final case class InspectOptions(
    @Recurse
    common: CatalogOptions,
) extends HasCatalogOptions

object Inspect extends CatalogCommand[InspectOptions] {
  override protected def commandName: String = "inspect"
  override protected def argumentName: String = "release URL"

  override protected def execute(
      options: InspectOptions,
      input: String,
      client: CatalogClient,
  ): String = {
    if !options.common.json && !OntologyReference.isUrl(input) && !input.contains("://") then
      correction(
        s"Inspect requires a release URL. Received '${CliFormatting.clean(input)}'.",
        "Copy the URL shown by search:\n  " + example(
          s"ohi search ${if input.startsWith("-") then "-- " else ""}${CliFormatting.quote(input)}",
        ) + "\n\nThen run 'ohi inspect <release-url>'.",
      )
    CatalogOutput.publication(client.inspect(input), options.common.json, colored)
  }
}

@HelpMessage(
  "Download an ontology file or all files in a lock\n" +
    "Existing entries in the directory are never overwritten.",
)
@ArgsName("[ontology-or-file-url]")
final case class GetOptions(
    @Recurse
    common: CatalogOptions,
    @HelpMessage(
      "Override the project format and LinkML fallback\nCannot be combined with a file URL or --lock",
    )
    @ValueDescription("<format>")
    format: Option[String] = None,
    @HelpMessage(
      "Download directory. Default: current directory.\nExisting files are never overwritten.",
    )
    @ValueDescription("<directory>")
    to: String = ".",
    @HelpMessage(
      "Download all files in a version-2 lock, including dependencies.\nReuse existing regular files whose SHA-256 matches the lock.",
    )
    @ValueDescription("<file>")
    lock: Option[String] = None,
) extends HasCatalogOptions

private[cli] class GetCommand extends CatalogCommand[GetOptions] {
  override protected def commandName: String = "get"
  override protected def argumentName: String = "ontology reference or file URL"

  override def run(options: GetOptions, remainingArgs: RemainingArgs): Unit = options.lock match {
    case None => super.run(options, remainingArgs)
    case Some(suppliedPath) =>
      if remainingArgs.all.nonEmpty then
        correction(
          "Use either an ontology reference, a file URL or --lock, without extra arguments.",
          s"Received ${remainingArgs.all.map(CliFormatting.quote(_)).mkString(", ")} and --lock ${CliFormatting.quote(suppliedPath)}.",
        )
      if options.format.nonEmpty then
        correction(
          "--format cannot be used with --lock.",
          "The lock already records its files:\n  " + example(invocation(Seq("--format" -> None))),
        )
      val path = try Path.of(suppliedPath)
      catch {
        case cause: InvalidPathException =>
          correction(s"Invalid lock path: ${cause.getMessage}.")
      }
      try {
        val client = new CatalogClient(config(options))
        val lock = LockFiles.read(path)
        if lock.formatVersion != 2 then {
          if !options.common.json then
            err(
              s"${CliFormatting.styled("Error:", "1;31", errorColored)} Lock downloads require format version 2.\n" +
                s"Lock: ${CliFormatting.styled(suppliedPath, "36", errorColored)}\n\n" +
                "For a project lock, run 'ohi lock --project <project-directory>'.\n" +
                "For a standalone lock, regenerate it with the application that created it.\n" +
                "Then repeat the download command.",
            )
          throw new LockException(
            LockError(
              code = LockErrorCode.UnsupportedVersion,
              operation = LockOperation.Read,
              path = Some(suppliedPath),
              message = s"Lock '$suppliedPath' requires format version 2 for downloads. " +
                "Rebuild a project lock with 'ohi lock --project <directory>'. " +
                "Regenerate a standalone lock with its originating application.",
            ),
          )
        }
        LockDownload.download(lock.files, options.to, client.get) match {
          case Right(result) =>
            printLine(LockDownloadOutput.result(result, options.common.json, colored))
          case Left(error) =>
            err(
              LockDownloadOutput.error(error, options.common.json, errorColored, Some(invocation())),
            )
        }
      } catch {
        case cause: (CatalogException | LockException) =>
          err(
            LockDownloadOutput.error(
              LockDownloadOutput.failure(Seq.empty, cause),
              options.common.json,
              errorColored,
              Some(invocation()),
            ),
          )
      }
  }

  override protected def execute(
      options: GetOptions,
      input: String,
      client: CatalogClient,
  ): String = {
    val url = if OntologyReference.isUrl(input) then {
      if options.format.nonEmpty then
        correction(
          "--format cannot be used with a file URL.",
          "The URL already selects the file:\n  " + example(invocation(Seq("--format" -> None))),
        )
      input
    } else {
      val metadata = new ProjectLock.Inspection(client.inspect)
      try
        reference(
          input,
          options.format,
          client,
          metadata,
          ProjectFormat.infer(Path.of("."), metadata),
        )._2.url.original
      catch {
        case failure: ProjectException => correction(failure.getMessage)
      }
    }
    CatalogOutput.delivery(
      FileDelivery.save(url, options.to, client.get),
      options.common.json,
      colored,
    )
  }
}

object Get extends GetCommand
