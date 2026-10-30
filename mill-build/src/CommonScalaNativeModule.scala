package millbuild

import coursier.maven.MavenRepository
import mill.scalanativelib.ScalaNativeModule
import millbuild.Version
import mill.*

/** Scala Native modules must be compiled one at a time. Otherwise, race conditions in scalac cause
  * random crashes.
  *
  * Not published to Maven: the release job leaves `LINKML_NATIVE` unset, which hides these modules.
  * The Scala Native build only feeds the shared library and the Python bindings.
  */
trait CommonScalaNativeModule extends ScalaNativeNightly, mill.api.DynamicModule {
  override def javaHome = Task {
    None
  }

  override def moduleDirectChildren: Seq[mill.api.Module] =
    super.moduleDirectChildren.filterNot(_.moduleSegments.last.value == "scoverage")
}

/** Scala Native at [[Version.scalaNative]], resolved from the nightly repository. */
trait ScalaNativeNightly extends ScalaNativeModule {
  def scalaNativeVersion = Version.scalaNative

  override def repositoriesTask = Task.Anon {
    super.repositoriesTask() :+ ScalaNativeNightly.repository
  }
}

object ScalaNativeNightly {
  val repository = MavenRepository("https://central.sonatype.com/repository/maven-snapshots/")
}
