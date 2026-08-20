package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks that the code emitted by [[ScalaGenerator]] actually compiles, by running the Scala 3
  * compiler over it. JVM-only, as it needs the compiler and the file system.
  */
class ScalaGeneratorCompileSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  override val skipModels: Map[String, String] = Map(
    "syntheticUris" ->
      "LNK-169: element names that are not valid Scala identifiers are emitted without escaping",
    "nonHermetic" ->
      "LNK-169: a type whose base has the same name generates a cyclic alias (`type Int = Int`)",
  )

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

  "ScalaGenerator" should {
    for entry <- ModelCatalogue.all do
      s"generate compilable code for model '${entry.name}'" in {
        processSkip(entry.name, "")
        val dir = os.temp.dir(prefix = "linkml-scala-src")
        val sources = ScalaGenerator(using entry.model).generate(ScalaGenerator.Options("generated")).map {
          (name, content) =>
            val file = dir / name
            os.write(file, content)
            file
        }.toSeq
        sources should not be empty
        val failure = compileScala(sources)
        withClue(s"compiler output:\n${failure.getOrElse("")}\n")(failure shouldBe None)
      }
  }
}
