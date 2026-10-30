package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks that the code emitted by [[ScalaGenerator]] actually compiles, by running the Scala 3
  * compiler over it. JVM-only, as it needs the compiler and the file system.
  */
class ScalaGeneratorCompileSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  override val skipModels: Map[String, String] = ScalaGeneratorCompileSpec.skipModels

  /** Compile the given Scala sources with the Scala 3 compiler. The test classpath is reused, so
    * the generated code is checked against the real `runtime` module it depends on.
    *
    * @return
    *   The compiler output if compilation failed, or None if it succeeded.
    */
  def compileScala(sources: Seq[os.Path]): Option[String] = {
    val out = os.temp.dir(prefix = "linkml-scala-out")
    val args = Array(
      "-classpath",
      System.getProperty("java.class.path"),
      "-d",
      out.toString,
    ) ++ sources.map(_.toString)
    val log = java.io.ByteArrayOutputStream()
    val reporter = Console.withErr(log) {
      Console.withOut(log)(dotty.tools.dotc.Driver().process(args))
    }
    Option.when(reporter.hasErrors)(log.toString)
  }

  /** Generate [[entry]] with [[options]] and compile the result. */
  private def compileModel(entry: ModelCatalogue.Entry, options: ScalaGenerator.Options): Unit = {
    val dir = os.temp.dir(prefix = "linkml-scala-src")
    val sources =
      ScalaGenerator(using entry.model).generate(options).map { (name, content) =>
        val file = dir / name
        os.write(file, content)
        file
      }.toSeq
    sources should not be empty
    val failure = compileScala(sources)
    withClue(s"compiler output:\n${failure.getOrElse("")}\n")(failure shouldBe None)
  }

  "ScalaGenerator" should {
    for
      entry <- ModelCatalogue.all
      skipLeafInterfaces <- Seq(true, false)
    do
      val variant = if skipLeafInterfaces then "" else " with leaf interfaces"
      s"generate compilable code for model '${entry.name}'$variant" in {
        processSkip(entry.name, "")
        compileModel(
          entry,
          ScalaGenerator.Options("generated", skipLeafInterfaces = skipLeafInterfaces),
        )
      }

    // Only the models with functions
    for entry <- ModelCatalogue.all.filter(_.model.root.functions.nonEmpty) do
      s"generate compilable code for model '${entry.name}' with errors as Either" in {
        processSkip(entry.name, "")
        compileModel(
          entry,
          ScalaGenerator.Options("generated", errorHandling = ErrorHandling.either),
        )
      }
  }
}

object ScalaGeneratorCompileSpec {
  private val slotUsageType =
    "LNK-232: Scala: a `slot_usage` that changes the type of a field gives code that does not " +
      "compile"

  val skipModels: Map[String, String] = Map(
    "requiredOverride" -> slotUsageType,
    "importedOverride" -> slotUsageType,
    "mixinFacets" -> slotUsageType,
    "multivaluedOverride" -> slotUsageType,
    "rangeOverride" -> slotUsageType,
  )
}
