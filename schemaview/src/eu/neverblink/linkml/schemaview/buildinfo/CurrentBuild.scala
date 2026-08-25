package eu.neverblink.linkml.schemaview.buildinfo

import org.virtuslab.yaml.Node

/** The build metadata of the LinkML-Scala distribution this code is part of.
  *
  * The version numbers come from [[BuildConstants]], which the build fills in, and the rest is read
  * off the running platform. Callers that know about additional components (beyond SchemaView) can
  * add more metadata with `copy`: the CLI adds `rdf4jVersion`, the shared library adds
  * `abiVersion`.
  */
object CurrentBuild {

  /** Everything this module can tell about the current distribution. */
  def info: BuildInfoImpl = BuildInfoImpl(
    linkmlScalaVersion = BuildConstants.version,
    metamodelVersion = BuildConstants.metamodelVersion,
    scalaVersion = BuildConstants.scalaVersion,
    scalaJsVersion = PlatformBuild.scalaJsVersion,
    platform = PlatformBuild.platform,
    runtime = PlatformBuild.runtime,
  )

  /** Build metadata as a YAML node, ready to be written out as JSON or YAML.
    *
    * Encoding lives here rather than in each caller so that every surface reports the same shape.
    * Turning the node into JSON needs the generator module, which sits above this one, so that last
    * step stays with the caller.
    */
  def node(build: BuildInfoImpl = info): Node = Codec.codec.encode(build)
}
