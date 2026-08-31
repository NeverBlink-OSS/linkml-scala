package eu.neverblink.linkml.schemaview.buildinfo

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[BuildInfo]] LinkML class
  *
  * @inheritdoc
  */
final case class BuildInfoImpl(
    @named("abi_version")
    abiVersion: Option[Int] = None,
    @named("linkml_scala_version")
    linkmlScalaVersion: String,
    @named("metamodel_version")
    metamodelVersion: String,
    platform: Platform,
    runtime: Option[String] = None,
    @named("scala_js_version")
    scalaJsVersion: Option[String] = None,
    @named("scala_version")
    scalaVersion: String,
) extends BuildInfo {

  override def infer(): BuildInfoImpl =
    this
}

/** Version and build metadata of the LinkML-Scala distribution that produced this report.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/build-info
  * @note
  *   Some slots are optional, because not every distribution contains every component. An absent
  *   slot means "this build does not include that component".
  */
abstract class BuildInfo {

  /** The version of the C ABI exported by the shared library, matching what `linkml_abi_version`
    * returns. Present in the native library and its Python bindings only.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  def abiVersion: Option[Int]

  /** The SemVer version of LinkML-Scala itself. Releases are plain versions like `1.2.3`. Builds
    * made between releases include the commit they were built from, like
    * `1.2.3-4-abcdef1-SNAPSHOT`.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  def linkmlScalaVersion: String

  /** The version of the LinkML metamodel this distribution was built against, taken from
    * `metamodel_version` in the LinkML `meta` schema.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  def metamodelVersion: String

  /** The kind of build this is, and therefore which of the optional slots can appear.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  def platform: Platform

  /** Human-readable description of the virtual machine or engine currently executing the code, for
    * example `OpenJDK 64-Bit Server VM 25.0.1` or `Node.js v24.2.0`. Useful for bug reports.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  def runtime: Option[String]

  /** The version of Scala.js this distribution was compiled with. JavaScript builds only.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  def scalaJsVersion: Option[String]

  /** The version of the Scala compiler this distribution was built with.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  def scalaVersion: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): BuildInfo
}
