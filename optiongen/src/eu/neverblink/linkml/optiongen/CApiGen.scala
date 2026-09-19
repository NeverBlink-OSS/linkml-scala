package eu.neverblink.linkml.optiongen

/** Generates the generator entry points of the C ABI from [[Entrypoints]].
  */
object CApiGen {

  def apply(catalog: Catalog): String = {
    val generators = catalog.generators.map(generator => generator.id -> generator).toMap
    val methods = Entrypoints.all.map(entry => render(entry, generators(entry.python)))

    s"""// AUTO-GENERATED from model/generator-options.yaml and the optiongen Entrypoints registry.
       |// Do not edit by hand - regenerate with LINKML_NATIVE=1 ./mill bindings.
       |package eu.neverblink.linkml.nativelib
       |
       |import scala.scalanative.unsafe.*
       |
       |/** The generator entry points of the C ABI, one per generator.
       |  *
       |  * All of them have the same shape: a schema handle, an options JSON that may be NULL for
       |  * defaults, and an error out-param. They return the generated document, or NULL with
       |  * `*error` set. Release returned strings with `linkml_free`.
       |  *
       |  * See [[LinkMlCApi]] for loading, linting and the lifecycle.
       |  */
       |object LinkMlCGenerators {
       |
       |${methods.mkString("\n")}}
       |""".stripMargin
  }

  private def render(entry: Entrypoints.Entrypoint, generator: GeneratorDef): String = {
    val options = generator.profiles(Interface.Native).parameters
    val listed =
      if options.isEmpty then "Takes no options."
      else
        "Options: " + options.map(parameter => s"`${parameter.selector.field}`").mkString(
          ", ",
        ) + "."
    val method = entry.python.split('_').toList match {
      case head :: tail => head + tail.map(_.capitalize).mkString
      case Nil => entry.python
    }

    val result =
      if entry.structured then " Returns a JSON object mapping filenames to content." else ""
    val description = Literals.commentLines(generator.description + result).mkString(" ")
    s"""  /** $description $listed */
       |  @exported("${entry.symbol}")
       |  def $method(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
       |    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.${entry.scalaMethod})
       |""".stripMargin
  }
}
