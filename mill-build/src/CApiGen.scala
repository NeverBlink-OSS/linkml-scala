package millbuild

/** Generates the generator entry points of the C ABI from [[Entrypoints]].
  */
object CApiGen {

  /** @param read
    *   reads a repository-relative path, so the caller decides where the sources come from
    */
  def apply(read: String => String): String = {
    val methods = Entrypoints.all.map(entry => render(entry, read(entry.source)))

    s"""// AUTO-GENERATED from mill-build/src/Entrypoints.scala and the generators' Options case
       |// classes. Do not edit by hand - regenerate with LINKML_NATIVE=1 ./mill bindings.
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

  private def render(entry: Entrypoints.Entrypoint, source: String): String = {
    val options = OptionsReader.fields(source, entry.generator, entry.source)
    val listed =
      if options.isEmpty then "Takes no options."
      else "Options: " + options.map(field => s"`${field.name}`").mkString(", ") + "."
    val method = entry.python.split('_').toList match {
      case head :: tail => head + tail.map(_.capitalize).mkString
      case Nil => entry.python
    }

    s"""  /** ${entry.cComment} $listed */
       |  @exported("${entry.symbol}")
       |  def $method(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
       |    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.${entry.scalaMethod})
       |""".stripMargin
  }
}
