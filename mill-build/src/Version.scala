package millbuild

/** Version lock file
  */
object Version {
  val scala = "3.9.0"
  val scalaJS = "1.22.0"
  val scalaNative = "0.5.12"
  val rdf4j = "6.1.0"

  require(
    scalaNative == "0.5.12",
    "Scala Native was upgraded. If it is 0.5.13 or later, delete nativelib/src/scala and the " +
      "runClasspath override in nativelib.native - the fix there is upstream now.",
  )
}
