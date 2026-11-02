package millbuild

/** Version lock file
  */
object Version {
  val scala = "3.9.0"
  val scalaJS = "1.22.0"

  /** A nightly, for calls from foreign threads (scala-native#5115), which is not in a release yet.
    * Nightlies come from [[ScalaNativeNightly.repository]] and are deleted after about three
    * months, so move to the next release, or a newer nightly, before then.
    */
  val scalaNative = "0.5.13-20261031-4e8508c-SNAPSHOT"

  /** The CLI of [[scalaNative]] failed to publish, so the sdist linker runs an older CLI on the
    * `tools` of [[scalaNative]]. Set back to [[scalaNative]] with the next nightly.
    */
  val scalaNativeCli = "0.5.13-20261029-75d9113-SNAPSHOT"
  val rdf4j = "6.1.0"
  val markdown = "0.4.9"
}
