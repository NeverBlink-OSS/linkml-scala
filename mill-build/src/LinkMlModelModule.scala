package millbuild

import mill.*
import mill.javalib.RunModule

/** A module for the Scala classes generated from one LinkML schema.
  *
  * The classes are checked in, because generating them needs the CLI, which depends on them. Run
  * `regenerate` on the module to generate them again, or `./mill regenerate` for all such modules.
  * If they ever stop compiling, revert them first.
  */
trait LinkMlModelModule extends Module {

  /** Runs the CLI that has the generators: `build.cli.jvm.runner`.
    *
    * It is a task and not the CLI module itself, because Mill would treat a `def` that returns a
    * module as a child module of this one.
    */
  def modelGeneratorCli: Task[RunModule.Runner]

  /** The schema to generate from: a path (absolute, or relative to the workspace root), or a name
    * like `linkml:meta`.
    */
  def modelSchema: T[String]

  /** The package of the generated classes. They are written to its directory under `src`. */
  def modelPackage: String

  /** Other files to generate, from [[modelSchema]] unless the output names a schema of its own. */
  def otherModelOutputs: Seq[ModelOutput] = Seq.empty

  /** Files that the Scala generator writes, but this module doesn't need. They are deleted. */
  def unusedModelFiles: Seq[String] = Seq.empty

  /** Generate the classes of this module and the [[otherModelOutputs]] from [[modelSchema]]. */
  def regenerate() = Task.Command {
    val root = mill.api.BuildCtx.workspaceRoot
    val cli = modelGeneratorCli()
    val schema = modelSchema()
    val to = moduleDir / "src" / os.SubPath(modelPackage.replace('.', '/'))
    cli.run(
      Seq("generate", "scala", "--clean", "--to", to.toString, "--package", modelPackage, schema),
      workingDir = root,
    )
    unusedModelFiles.foreach(file => os.remove(to / file))
    otherModelOutputs.foreach { output =>
      cli.run(
        Seq("generate") ++ output.generator.split(' ') ++
          Seq("--to", output.file.toString, output.schema.getOrElse(schema)),
        workingDir = root,
      )
    }
  }
}

/** A file that [[LinkMlModelModule.regenerate]] writes with a generator other than Scala.
  *
  * @param generator
  *   the generator name, with its options if any
  * @param schema
  *   the schema, if not the module's own
  */
final case class ModelOutput(generator: String, file: os.Path, schema: Option[String] = None)
