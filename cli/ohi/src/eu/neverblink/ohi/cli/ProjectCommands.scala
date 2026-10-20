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

@HelpMessage("Create ohi.yaml in a project directory.")
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

@HelpMessage("Add a publication's artifact to ohi.yaml, update the lock and download the artifact.")
@ArgsName("<base-url>")
final case class AddOptions(
    @HelpMessage("URL of the artifact to add, as listed by ohi inspect.")
    artifact: String,
    @HelpMessage("Directory to save the artifact in.")
    to: String,
    @HelpMessage("Project directory containing ohi.yaml. Default: current directory.")
    project: String = ".",
    @HelpMessage("Emit one JSON result or error instead of human-readable output.")
    json: Boolean = false,
) extends ProjectOptions

object Add extends ProjectCommand[AddOptions] {
  override protected def operation: ProjectOperation = ProjectOperation.Add
  override protected def inputCount: Range.Inclusive = 1 to 1
  override protected def directory(options: AddOptions, inputs: Seq[String]): String =
    options.project
  override protected def execute(
      options: AddOptions,
      inputs: Seq[String],
      project: Project,
  ): ProjectResult = {
    val client = new CatalogClient()
    project.add(inputs.head, options.artifact, options.to, client.inspect, client.get)
  }
}

@HelpMessage("Rebuild ohi.lock.json from ohi.yaml. Downloads nothing.")
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
