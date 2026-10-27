package eu.neverblink.ohi.cli

import caseapp.*
import caseapp.core.Error
import eu.neverblink.linkml.cli.BaseCommand
import eu.neverblink.ohi.catalog.{CatalogClient, CatalogException}
import eu.neverblink.ohi.cli.generated.projecterror.ProjectOperation
import eu.neverblink.ohi.cli.generated.projectresult.ProjectResult
import eu.neverblink.ohi.model.LockException

import java.nio.file.{InvalidPathException, Path}

trait ProjectOptions {
  def json: Boolean
}

/** Base class of the `init`, `add` and `lock` commands. Prints the command's result, or its error
  * with recovery steps, as text or JSON.
  */
abstract class ProjectCommand[T <: ProjectOptions: {Parser, Help}] extends BaseCommand[T] {
  protected def operation: ProjectOperation
  protected def inputCount: Range.Inclusive
  protected def directory(options: T, inputs: Seq[String]): String
  protected def execute(options: T, inputs: Seq[String], project: Project): ProjectResult
  private def commandName: String = operation.toString.toLowerCase

  override final def names: List[List[String]] = List(List(commandName))

  override final def error(message: Error): Nothing =
    err(s"${message.message}\nRun 'ohi $commandName --help' for usage.")

  override final def run(options: T, remainingArgs: RemainingArgs): Unit = {
    val inputs = remainingArgs.all
    if !inputCount.contains(inputs.size) then
      err(s"Invalid number of arguments for $commandName. Run 'ohi $commandName --help' for usage.")
    val path = try Path.of(directory(options, inputs)).toAbsolutePath.normalize()
    catch {
      case cause: InvalidPathException =>
        err(
          s"Invalid project directory: ${cause.getMessage}. Run 'ohi $commandName --help' for usage.",
        )
    }
    val project = new Project(path, operation)
    try printLine(ProjectOutput.result(execute(options, inputs, project), options.json))
    catch {
      case cause: (ProjectException | CatalogException | LockException | CliFileException) =>
        err(ProjectOutput.error(project.failure(cause), options.json))
    }
  }
}

@HelpMessage("Create ohi.yaml in a project directory")
@ArgsName("[directory]")
final case class InitOptions(
    @HelpMessage("Emit one JSON result or error instead of human-readable output.")
    json: Boolean = false,
) extends ProjectOptions

object Init extends ProjectCommand[InitOptions] {
  override protected def operation: ProjectOperation = ProjectOperation.Init
  override protected def inputCount: Range.Inclusive = 0 to 1
  override protected def directory(options: InitOptions, inputs: Seq[String]): String =
    inputs.headOption.getOrElse(".")
  override protected def execute(
      options: InitOptions,
      inputs: Seq[String],
      project: Project,
  ): ProjectResult = project.init(createParents = inputs.nonEmpty)
}

@HelpMessage(
  "Add an ontology file to the project\n" +
    "Adds it to ohi.yaml, updates ohi.lock.json and downloads the artifact.",
)
@ArgsName("<ontology-or-base-url>")
final case class AddOptions(
    @HelpMessage("Artifact URL to add. Required with a publication URL.")
    artifact: Option[String] = None,
    @HelpMessage("Format to download. Required for an ontology reference.")
    format: Option[String] = None,
    @HelpMessage("Destination directory. Default: current directory.")
    to: String = ".",
    @HelpMessage("Project directory containing ohi.yaml. Default: current directory.")
    project: String = ".",
    @HelpMessage("Emit one JSON result or error instead of human-readable output.")
    json: Boolean = false,
) extends ProjectOptions

private[cli] class AddCommand extends ProjectCommand[AddOptions] {
  override protected def operation: ProjectOperation = ProjectOperation.Add
  override protected def inputCount: Range.Inclusive = 1 to 1
  override protected def directory(options: AddOptions, inputs: Seq[String]): String =
    options.project

  protected def client(): CatalogClient = new CatalogClient()

  override protected def execute(
      options: AddOptions,
      inputs: Seq[String],
      project: Project,
  ): ProjectResult = {
    val catalog = client()
    val input = inputs.head
    if OntologyReference.isUrl(input) then {
      if options.format.nonEmpty then
        error(Error.Other("--format cannot be used with a publication URL."))
      val artifact = options.artifact.getOrElse(
        error(Error.Other("Required option --artifact not specified")),
      )
      project.add(input, artifact, options.to, catalog.inspect, catalog.get)
    } else {
      if options.artifact.nonEmpty then
        error(Error.Other("--artifact cannot be used with an ontology reference."))
      val (publication, artifact) =
        OntologyReference.resolve(input, options.format, catalog.search, catalog.inspect)
          .fold(message => error(Error.Other(message)), identity)
      project.add(
        publication.baseUrl.original,
        artifact.url.original,
        options.to,
        url => if url == publication.baseUrl.original then publication else catalog.inspect(url),
        catalog.get,
      )
    }
  }
}

object Add extends AddCommand

@HelpMessage("Rebuild ohi.lock.json from ohi.yaml\nDownloads nothing.")
final case class LockOptions(
    @HelpMessage("Project directory containing ohi.yaml. Default: current directory.")
    project: String = ".",
    @HelpMessage("Emit one JSON result or error instead of human-readable output.")
    json: Boolean = false,
) extends ProjectOptions

object Lock extends ProjectCommand[LockOptions] {
  override protected def operation: ProjectOperation = ProjectOperation.Lock
  override protected def inputCount: Range.Inclusive = 0 to 0
  override protected def directory(options: LockOptions, inputs: Seq[String]): String =
    options.project
  override protected def execute(
      options: LockOptions,
      inputs: Seq[String],
      project: Project,
  ): ProjectResult = project.refresh(new CatalogClient().inspect)
}
