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
       |// classes. Do not edit by hand - regenerate with ./mill bindings.
       |package eu.neverblink.linkml.nativelib;
       |
       |import org.graalvm.nativeimage.IsolateThread;
       |import org.graalvm.nativeimage.c.function.CEntryPoint;
       |import org.graalvm.nativeimage.c.type.CCharPointer;
       |import org.graalvm.nativeimage.c.type.CCharPointerPointer;
       |import org.graalvm.nativeimage.c.type.CConst;
       |
       |/**
       | * The generator entry points of the C ABI, one per generator.
       | *
       | * <p>All of them have the same shape: a schema handle, an options JSON that may be NULL for
       | * defaults, and an error out-param. They return the generated document, or NULL with {@code
       | * *error} set. Release returned strings with {@code linkml_free}.
       | *
       | * <p>See {@link LinkMlCApi} for loading, linting and the lifecycle.
       | */
       |public final class LinkMlCGenerators {
       |
       |    private LinkMlCGenerators() {}
       |
       |${methods.mkString("\n")}}
       |""".stripMargin
  }

  private def render(entry: Entrypoints.Entrypoint, source: String): String = {
    val options = OptionsReader.fields(source, entry.generator, entry.source)
    val listed =
      if options.isEmpty then "Takes no options."
      else "Options: " + options.map(field => s"{@code ${field.name}}").mkString(", ") + "."

    s"""    /** ${entry.cComment} $listed */
       |    @CEntryPoint(name = "${entry.symbol}")
       |    static CCharPointer ${entry.python.split('_').toList match {
        case head :: tail => head + tail.map(_.capitalize).mkString
        case Nil => entry.python
      }}(
       |            IsolateThread thread,
       |            long handle,
       |            @CConst CCharPointer options,
       |            CCharPointerPointer error) {
       |        return LinkMlCApi.document(handle, options, error, LinkMlNativeApi::${entry.scalaMethod});
       |    }
       |""".stripMargin
  }
}
