package eu.neverblink.ohi.cli

import caseapp.*
import caseapp.core.Error
import eu.neverblink.linkml.cli.BaseCommand
import eu.neverblink.linkml.runtime.{Uri, stringify}
import eu.neverblink.ohi.catalog.{CatalogClient, CatalogException}
import eu.neverblink.ohi.model.{CatalogErrorJson, LockException, LockFiles}
import eu.neverblink.ohi.model.generated.config.CatalogConfig
import eu.neverblink.ohi.model.generated.lockerror.{LockError, LockErrorCode, LockOperation}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest

import java.nio.file.{InvalidPathException, Path}

final case class CatalogOptions(
    @HelpMessage("Emit one JSON result or error instead of human-readable output.")
    json: Boolean = false,
    @HelpMessage("Request timeout in milliseconds, from 1 to 2147483647. Default: 30000.")
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
abstract class CatalogCommand[T <: HasCatalogOptions: {Parser, Help}] extends BaseCommand[T] {
  protected def commandName: String
  protected def argumentName: String

  override final def names: List[List[String]] = List(List(commandName))

  override final def error(message: Error): Nothing =
    err(s"${message.message}\nRun 'ohi $commandName --help' for usage.")

  protected def config(options: T): CatalogConfig =
    CatalogConfig(timeoutMs = options.common.timeoutMs)

  protected def execute(options: T, input: String, client: CatalogClient): String

  override def run(options: T, remainingArgs: RemainingArgs): Unit = {
    val inputs = remainingArgs.all
    if inputs.size != 1 then
      err(
        s"$commandName requires exactly one $argumentName, but ${inputs.size} were given. " +
          s"Run 'ohi $commandName --help' for usage.",
      )
    try printLine(execute(options, inputs.head, new CatalogClient(config(options))))
    catch {
      case failure: CatalogException =>
        val error = failure.error
        err(
          if options.common.json then CatalogErrorJson.encode(error)
          else
            s"${stringify(error.operation)} failed (${stringify(error.code)}): ${error.message}" +
              error.url.fold("")(url => s"\nURL: $url") +
              s"\nCheck the supplied URL and options. Run 'ohi $commandName --help' for usage.",
        )
      case failure: CliFileException =>
        err(
          if options.common.json then CatalogOutput.fileError(failure.error)
          else failure.error.message,
        )
    }
  }
}

@HelpMessage("Search ontology publications.")
@ArgsName("<query>")
final case class SearchOptions(
    @Recurse
    common: CatalogOptions,
    @HelpMessage("Complete HTTP search endpoint. Supply with --query-by, or omit both for ohi.dev.")
    searchUrl: Option[String] = None,
    @HelpMessage(
      "Comma-separated indexed fields. Supply with --search-url, or omit both for ohi.dev.",
    )
    queryBy: Option[String] = None,
    @HelpMessage("One-based page number. Default: 1.")
    page: Option[Int] = None,
    @HelpMessage("Page size, from 1 to 250. Default: 10.")
    perPage: Option[Int] = None,
    @HelpMessage("Backend filter expression.")
    filterBy: Option[String] = None,
    @HelpMessage("Backend sort expression.")
    sortBy: Option[String] = None,
    @HelpMessage("Backend facet expression.")
    facetBy: Option[String] = None,
    @HelpMessage("Maximum values per facet, at least 1. Omitted uses the backend default.")
    maxFacetValues: Option[Int] = None,
) extends HasCatalogOptions

object Search extends CatalogCommand[SearchOptions] {
  override protected def commandName: String = "search"
  override protected def argumentName: String = "query"

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
  ): String = CatalogOutput.search(
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
  )
}

@HelpMessage("Inspect an exact ontology publication URL.")
@ArgsName("<base-url>")
final case class InspectOptions(
    @Recurse
    common: CatalogOptions,
) extends HasCatalogOptions

object Inspect extends CatalogCommand[InspectOptions] {
  override protected def commandName: String = "inspect"
  override protected def argumentName: String = "publication URL"

  override protected def execute(
      options: InspectOptions,
      input: String,
      client: CatalogClient,
  ): String = CatalogOutput.publication(client.inspect(input), options.common.json)
}

@HelpMessage("Download a file or all files in a lock.")
@ArgsName("[file-url]")
final case class GetOptions(
    @Recurse
    common: CatalogOptions,
    @HelpMessage("Destination directory.")
    to: String,
    @HelpMessage("Download every file in a version-2 lock.")
    lock: Option[String] = None,
) extends HasCatalogOptions

object Get extends CatalogCommand[GetOptions] {
  override protected def commandName: String = "get"
  override protected def argumentName: String = "file URL"

  override def run(options: GetOptions, remainingArgs: RemainingArgs): Unit = options.lock match {
    case None => super.run(options, remainingArgs)
    case Some(suppliedPath) =>
      if remainingArgs.all.nonEmpty then
        err(
          "Use either a file URL or --lock, without extra arguments. Run 'ohi get --help' for usage.",
        )
      val path = try Path.of(suppliedPath)
      catch {
        case cause: InvalidPathException =>
          err(s"Invalid lock path: ${cause.getMessage}. Run 'ohi get --help' for usage.")
      }
      try {
        val client = new CatalogClient(config(options))
        val lock = LockFiles.read(path)
        if lock.formatVersion != 2 then
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
        LockDownload.download(lock.files, options.to, client.get) match {
          case Right(result) => printLine(LockDownloadOutput.result(result, options.common.json))
          case Left(error) => err(LockDownloadOutput.error(error, options.common.json))
        }
      } catch {
        case cause: (CatalogException | LockException) =>
          err(
            LockDownloadOutput.error(
              LockDownloadOutput.failure(Seq.empty, cause),
              options.common.json,
            ),
          )
      }
  }

  override protected def execute(
      options: GetOptions,
      input: String,
      client: CatalogClient,
  ): String =
    CatalogOutput.delivery(FileDelivery.save(input, options.to, client.get), options.common.json)
}
