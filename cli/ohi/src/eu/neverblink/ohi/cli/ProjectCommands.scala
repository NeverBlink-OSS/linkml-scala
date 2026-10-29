package eu.neverblink.ohi.cli

import caseapp.*
import caseapp.core.Error
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
abstract class ProjectCommand[T <: ProjectOptions: {Parser, Help}] extends OhiCommand[T] {
  protected def operation: ProjectOperation
  protected def inputCount: Range.Inclusive
  protected def directory(options: T, inputs: Seq[String]): String
  protected def execute(options: T, inputs: Seq[String], project: Project): ProjectResult
  override protected def commandName: String = operation.toString.toLowerCase

  override final def names: List[List[String]] = List(List(commandName))

  override final def run(options: T, remainingArgs: RemainingArgs): Unit = {
    val inputs = remainingArgs.all
    if inputs.isEmpty && inputCount.start > 0 then shortHelp()
    if !inputCount.contains(inputs.size) then
      if operation == ProjectOperation.Lock then
        correction(
          "Select the project directory with --project.",
          if inputs.size == 1 then
            "  " + example(invocation(Seq("--project" -> Some(inputs.head)), Some(Seq.empty)))
          else "",
        )
      else
        correction(if operation == ProjectOperation.Init then "Init accepts at most one directory."
        else "Add accepts one ontology reference or release URL.")
    val path = try Path.of(directory(options, inputs)).toAbsolutePath.normalize()
    catch {
      case cause: InvalidPathException =>
        correction(
          s"Invalid project directory: ${cause.getMessage}.",
        )
    }
    val project = new Project(path, operation)
    try printLine(ProjectOutput.result(execute(options, inputs, project), options.json, colored))
    catch {
      case cause: (ProjectException | CatalogException | LockException | CliFileException) =>
        err(
          ProjectOutput.error(
            project.failure(cause),
            options.json,
            errorColored,
            Some(cause.getMessage),
            project.recoveryCommands(cause),
          ),
        )
    }
  }
}

@HelpMessage("Create ohi.yaml in a project directory")
@ArgsName("[directory]")
final case class InitOptions(
    @HelpMessage("Write JSON output")
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
    "Adds it to ohi.yaml, updates ohi.lock.json and downloads the file.",
)
@ArgsName("<ontology-or-release-url>")
final case class AddOptions(
    @HelpMessage("Required with a release URL\nCannot be combined with an ontology reference")
    @ValueDescription("<file-url>")
    artifact: Option[String] = None,
    @HelpMessage(
      "Override the project format and LinkML fallback\nCannot be combined with a release URL",
    )
    @ValueDescription("<format>")
    format: Option[String] = None,
    @HelpMessage(
      "Download directory. Default: current directory.\nExisting files are never overwritten.",
    )
    @ValueDescription("<directory>")
    to: String = ".",
    @HelpMessage("Existing project directory. Default: current directory.")
    @ValueDescription("<directory>")
    project: String = ".",
    @HelpMessage("Write JSON output")
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
        correction(
          "--format cannot be used with a release URL.",
          if options.artifact.nonEmpty then
            "The file is selected by --artifact:\n  " + example(invocation(Seq("--format" -> None)))
          else
            "Choose a file with --artifact. Inspect the release:\n  " + example(
              "ohi inspect " + CliFormatting.quote(input),
            ),
        )
      val artifact = options.artifact.getOrElse(
        correction(
          "Choose a file with --artifact.",
          "Inspect the release to choose a file:\n  " + example(
            "ohi inspect " + CliFormatting.quote(input),
          ) +
            "\n\nThen add its exact file URL:\n  " +
            example(invocation(Seq("--artifact" -> Some("<file-url>")))),
          invalid = false,
        ),
      )
      project.add(input, artifact, options.to, catalog.inspect, catalog.get)
    } else {
      if options.artifact.nonEmpty then
        error(Error.Other("--artifact cannot be used with an ontology reference."))
      val metadata = new ProjectLock.Inspection(catalog.inspect)
      val (publication, artifact) = reference(
        input,
        options.format,
        catalog,
        metadata,
        ProjectFormat.infer(project.directory, metadata),
      )
      project.add(
        publication.baseUrl.original,
        artifact.url.original,
        options.to,
        metadata,
        catalog.get,
      )
    }
  }
}

object Add extends AddCommand

@HelpMessage("Rebuild ohi.lock.json from ohi.yaml without downloading")
final case class LockOptions(
    @HelpMessage("Directory containing ohi.yaml\nDefault: current directory")
    @ValueDescription("<directory>")
    project: String = ".",
    @HelpMessage("Write JSON output")
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
