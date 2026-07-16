package eu.neverblink.linkml.benchmark

import org.openjdk.jmh.annotations.*
import os.Path

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

@OutputTimeUnit(TimeUnit.MINUTES)
@Warmup(iterations = 5)
@Measurement(iterations = 5 * 10)
class CliBench extends CommonParams {
  val repoRoot: Path =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).map(os.Path.apply).getOrElse(os.pwd)

  val modelsDir: Path = repoRoot / ".." / "linkml-benchmark-schemas"

  assume(
    os.exists(modelsDir),
    "Did not find the linkml-benchmark-schemas directory in the expected location. " +
      "Run `cd ..; git clone https://github.com/NeverBlink-OSS/linkml-benchmark-schemas.git` to fix",
  )

  private val devNull: Path = os.root / "dev" / "null"

  private val linkmlNativeImage =
    repoRoot / "out" / "cli" / "jvm" / "nativeImage.dest" / "linkml-scala"
  private val linkmlPy = repoRoot / ".venv" / "bin" / "linkml"

  assume(
    os.exists(linkmlNativeImage),
    "No native image executable found in the expected directory. " +
      "Run `./mill cli.jvm.nativeImage` to fix",
  )
  assume(
    os.exists(linkmlPy),
    "No python linkml executable found in the expected directory. " +
      "Run `uv venv; source venv/bin/activate; uv pip install -r requirements.txt` to fix",
  )
  assume(
    os.exists(modelsDir),
    "No model dir found to run benchmarks with",
  )

  @Param(models)
  var model: String = uninitialized

  @Setup
  def setup(): Unit = {}

  @Benchmark
  def jsonSchemaNativeImage(): Unit = {
    os.call(
      (
        "linkml-scala",
        "generate",
        "json-schema",
        modelsDir / model / "main.yaml",
      ),
      stdout = os.PathRedirect(devNull),
    )
  }

  @Benchmark
  def jsonSchemaPython(): Unit = {
    os.call(
      (
        linkmlPy,
        "generate",
        "json-schema",
        modelsDir / model / "main.yaml",
      ),
      stdout = os.PathRedirect(devNull),
    )
  }

  @Benchmark
  def shaclNativeImage(): Unit = {
    os.call(
      (
        "linkml-scala",
        "generate",
        "shacl",
        modelsDir / model / "main.yaml",
      ),
      stdout = os.PathRedirect(devNull),
    )
  }

  @Benchmark
  def shaclPython(): Unit = {
    os.call(
      (
        linkmlPy,
        "generate",
        "shacl",
        modelsDir / model / "main.yaml",
      ),
      stdout = os.PathRedirect(devNull),
    )
  }
}
