package eu.neverblink.linkml.schemaview.buildinfo

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.Node

/** The build metadata of the LinkML-Scala distribution this code is part of.
  *
  * The version numbers come from [[BuildConstants]], which the build fills in, and the rest is read
  * off the running platform. Callers that know about additional components (beyond SchemaView) can
  * add more metadata with `copy`: the CLI adds `rdf4jVersion`, the shared library adds
  * `abiVersion`.
  */
object CurrentBuild {

  private val codec: LinkmlYamlCodec[BuildInfoImpl] = LinkmlYamlCodec.derived

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
    */
  def node(build: BuildInfoImpl = info): Node = codec.encode(build)
}
