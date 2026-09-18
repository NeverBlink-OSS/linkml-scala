package millbuild

import mill.scalanativelib.ScalaNativeModule
import millbuild.Version
import mill.*
import mill.javalib.PublishModule

/** Scala Native modules must be compiled one at a time. Otherwise, race conditions in scalac cause
  * random crashes.
  */
trait CommonScalaNativeModule extends ScalaNativeModule, PublishModule, mill.api.DynamicModule {
  def scalaNativeVersion = Version.scalaNative

  override def javaHome = Task {
    None
  }

  override def moduleDirectChildren: Seq[mill.api.Module] =
    super.moduleDirectChildren.filterNot(_.moduleSegments.last.value == "scoverage")
}
