package eu.neverblink.ohi.cli

import caseapp.*
import caseapp.core.Error
import eu.neverblink.ohi.catalog.{CatalogClient, CatalogException}
import eu.neverblink.ohi.cli.generated.projecterror.ProjectOperation
import eu.neverblink.ohi.cli.generated.projectresult.ProjectResult
import eu.neverblink.ohi.model.LockException
import eu.neverblink.ohi.model.generated.config.CatalogConfig

import java.nio.file.{InvalidPathException, Path}

trait ProjectOptions {
  def json: Boolean
}

/** Base class of the `init`, `add`, `sync` and `lock` commands. Prints the command's result, or its
  * error with recovery steps, as text or JSON.
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
      if operation == ProjectOperation.Lock || operation == ProjectOperation.Sync then
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
    try {
      val result = execute(options, inputs, project)
      printLine(
        ProjectOutput.result(
          result,
          options.json,
          colored,
          downloadDirectory = project.downloadDirectory,
          added = project.addedDependency,
        ),
      )
    } catch {
      case cause: (ProjectException | CatalogException | LockException | CliFileException |
            LockDownload.InstallationException) =>
        err(
          ProjectOutput.error(
            project.failure(cause, invocation()),
            options.json,
            errorColored,
            Some(cause.getMessage),
            Some(project.recoveryCommand(cause, invocation())),
            downloadDirectory = project.downloadDirectory,
          ),
        )
    }
  }
}

@HelpMessage("Create ohi.yaml in a project directory")
@ArgsName("[directory]")
final case class InitOptions(
    @Name("default_format")
    @HelpMessage(
      "Default artifact format for new projects. Default: linkml-yaml.\nExisting declarations are preserved.",
    )
    @ValueDescription("<format>")
    defaultFormat: String = "linkml-yaml",
    @HelpMessage(
      "Project-relative download directory for new projects. Default: .ohi.\nExisting declarations are preserved.",
    )
    @ValueDescription("<directory>")
    to: String = ".ohi",
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
  ): ProjectResult = {
    val defaultFormat = OntologyReference.parseFormat(options.defaultFormat)
      .fold(problem => error(Error.Other(problem.message)), ProjectFormat.toDeclaration)
    project.init(
      createParents = inputs.nonEmpty,
      defaultFormat = defaultFormat,
      downloadDir = options.to,
    )
  }
}

@HelpMessage(
  "Add an ontology to the project\n" +
    "Records the selection, updates ohi.lock.json and installs all locked files.",
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
      options.artifact.getOrElse(
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

    } else {
      if options.artifact.nonEmpty then
        error(Error.Other("--artifact cannot be used with an ontology reference."))
      options.format.foreach(label =>
        OntologyReference.parseFormat(label).left.foreach(_ => formatCorrection(label)),
      )
    }
    project.add(
      input,
      options.artifact,
      options.format,
      catalog.inspect,
      catalog.get,
    )
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

private[cli] class LockCommand extends ProjectCommand[LockOptions] {
  override protected def operation: ProjectOperation = ProjectOperation.Lock
  override protected def inputCount: Range.Inclusive = 0 to 0
  override protected def directory(options: LockOptions, inputs: Seq[String]): String =
    options.project
  override protected def execute(
      options: LockOptions,
      inputs: Seq[String],
      project: Project,
  ): ProjectResult = {
    val catalog = client()
    project.refresh(catalog.inspect)
  }

  protected def client(): CatalogClient = new CatalogClient()
}

object Lock extends LockCommand

@HelpMessage(
  "Install files from ohi.lock.json\n" +
    "Update the lock if it is missing or ohi.yaml dependencies have changed.",
)
final case class SyncOptions(
    @HelpMessage("Directory containing ohi.yaml or ohi.lock.json\nDefault: current directory")
    @ValueDescription("<directory>")
    project: String = ".",
    @HelpMessage("Create a missing ohi.yaml from the lock's direct selections")
    init: Boolean = false,
    @HelpMessage("Request timeout in milliseconds (Default: 30000)")
    @ValueDescription("<number>")
    timeoutMs: Option[Int] = None,
    @HelpMessage("Color human output. Default: auto")
    @ValueDescription("<auto|never>")
    color: CliColor = CliColor.Auto,
    @HelpMessage("Write JSON output")
    json: Boolean = false,
) extends ProjectOptions

private[cli] class SyncCommand extends ProjectCommand[SyncOptions] {
  override protected def operation: ProjectOperation = ProjectOperation.Sync
  override protected def inputCount: Range.Inclusive = 0 to 0
  override protected def directory(options: SyncOptions, inputs: Seq[String]): String =
    options.project

  protected def client(options: SyncOptions): CatalogClient =
    new CatalogClient(CatalogConfig(timeoutMs = options.timeoutMs))

  override protected def execute(
      options: SyncOptions,
      inputs: Seq[String],
      project: Project,
  ): ProjectResult = {
    val catalog = client(options)
    project.sync(catalog.inspect, catalog.get, options.init)
  }
}

object Sync extends SyncCommand
