package millbuild

import mill.javalib.PublishModule
import mill.scalajslib.ScalaJSModule
import mill.scalajslib.api.ModuleKind
import millbuild.Version
import mill.*

trait CommonScalaJsModule extends ScalaJSModule, PublishModule {
  def scalaJSVersion = Version.scalaJS

  def moduleKind = Task {
    ModuleKind.ESModule
  }
}
