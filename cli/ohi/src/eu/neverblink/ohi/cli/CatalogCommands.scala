package eu.neverblink.ohi.cli

import caseapp.*
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.{Uri, stringify}
import eu.neverblink.ohi.catalog.{CatalogClient, CatalogException, Reference}
import eu.neverblink.ohi.cli.generated.delivery.DeliveryResult
import eu.neverblink.ohi.model.generated.config.CatalogConfig
import eu.neverblink.ohi.model.generated.error.{CatalogError, CatalogErrorCode, CatalogOperation}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest

import java.nio.file.Path

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

  // Explain the SDK's input failures in terms of command options.
  protected def inputCorrection(options: T, error: CatalogError): Unit = ()

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
          inputCorrection(options, error)
        err(
          if options.common.json then
            JsonUtil.yamlToJson(ErrorOutput.catalogErrorCodec.encode(error))
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
            s"$diagnostic\n\n$recovery" +
              (if commandName == "get" then "\n  " + example(invocation()) else "")
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
            s"$diagnostic\n\n$recovery" +
              (if commandName == "get" then "\n  " + example(invocation()) else "")
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
    @HelpMessage("Search every release instead of each ontology's latest version")
    allReleases: Boolean = false,
) extends HasCatalogOptions

object Search extends CatalogCommand[SearchOptions] {
  override protected def commandName: String = "search"
  override protected def argumentName: String = "query"

  override protected def inputCorrection(options: SearchOptions, error: CatalogError): Unit =
    if error.operation == CatalogOperation.Configure then {
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
          allReleases = options.allReleases,
        ),
      ),
      options.common.json,
      SearchOutput.width(environment.get("COLUMNS")),
      colored,
      options.allReleases,
    )
  }
}

@HelpMessage("Show a release's files and dependencies")
@ArgsName("<ontology-or-release-url>")
final case class InspectOptions(
    @Recurse
    common: CatalogOptions,
) extends HasCatalogOptions

private[cli] class InspectCommand extends CatalogCommand[InspectOptions] {
  override protected def commandName: String = "inspect"
  override protected def argumentName: String = "ontology reference or release URL"

  // The search server's largest page. No current ontology has more releases than that. The most
  // is 100, as of November 2026.
  private val releasesPerPage = 250

  override protected def execute(
      options: InspectOptions,
      input: String,
      client: CatalogClient,
  ): String =
    if OntologyReference.isUrl(input) || input.contains("://") then
      CatalogOutput.publication(client.inspect(input), options.common.json, colored)
    else {
      val (namespace, name, version) =
        OntologyReference.parse(input).fold(problem => correction(problem.message), identity)
      val publication = client.inspect(input)
      if options.common.json then CatalogOutput.publication(publication, json = true)
      else {
        // Without a version, inspect already returned the latest version.
        val latest =
          if version.isEmpty then Some(publication.baseUrl.original)
          else client.latest(s"$namespace/$name").map(_.baseUrl.original)
        val releases = client.search(
          SearchRequest(
            q = "*",
            perPage = Some(releasesPerPage),
            filterBy = Some(Reference.filter(namespace, name)),
            allReleases = true,
          ),
        ).hits.filter(hit => hit.namespace == namespace && hit.name == name)
        CatalogOutput.publication(
          publication,
          json = false,
          colored,
          Some(CatalogOutput.Versions(latest, releases)),
        )
      }
    }
}

object Inspect extends InspectCommand

@HelpMessage(
  "Download an ontology file\n" +
    "Existing entries in the directory are never overwritten.",
)
@ArgsName("<ontology-or-file-url>")
final case class GetOptions(
    @Recurse
    common: CatalogOptions,
    @HelpMessage(
      "Override the project format and LinkML fallback\nCannot be combined with a file URL",
    )
    @ValueDescription("<format>")
    format: Option[String] = None,
    @HelpMessage(
      "Download directory override, relative to the current directory.\nDefault: download_dir in cwd ohi.yaml, otherwise .ohi.",
    )
    @ValueDescription("<directory>")
    to: Option[String] = None,
) extends HasCatalogOptions

private[cli] class GetCommand extends CatalogCommand[GetOptions] {
  override protected def commandName: String = "get"
  override protected def argumentName: String = "ontology reference or file URL"

  override protected def execute(
      options: GetOptions,
      input: String,
      client: CatalogClient,
  ): String = {
    if OntologyReference.isUrl(input) && options.format.nonEmpty then
      correction(
        "--format cannot be used with a file URL.",
        "The URL already selects the file:\n  " + example(invocation(Seq("--format" -> None))),
      )
    try {
      val cwd = Path.of(".")
      lazy val declaration = DeclarationFiles.readIfPresent(cwd)
      val destination = options.to.map(DownloadDestination(_, managed = false)).getOrElse {
        declaration.fold(DownloadDestination.standalone(cwd))(
          DownloadDestination.fromProject(cwd, _),
        )
      }
      val metadata = new ProjectLock.Inspection(client.inspect)
      val (publication, artifact) = if OntologyReference.isUrl(input) then {
        val owner = HubArtifactPath.artifact(input, destination.directory)
        metadata.selected(
          Seq(
            eu.neverblink.ohi.model.generated.lock.ArtifactSelectionImpl(
              Uri(owner.baseUrl),
              Uri(input),
            ),
          ),
        ).head
      } else {
        reference(
          input,
          options.format,
          metadata,
          declaration.map(value => ProjectFormat.toModel(value.defaultFormat)),
        )
      }
      val plan = LockDownload.prepare(
        Seq(ProjectLock.file(publication, artifact)),
        destination.directory,
        destination.managed,
      )
      LockDownload.ensureIgnore(plan)
      val files = LockDownload.install(plan, client.get)
      if options.common.json then
        CatalogOutput.delivery(DeliveryResult(files.head.path, files.head.url))
      else LockDownloadOutput.summary(files, colored, options.to)
    } catch {
      case failure: ProjectException => correction(failure.getMessage)
      case failure: LockDownload.InstallationException => throw failure.cause
    }
  }
}

object Get extends GetCommand
