package millbuild

import com.goyeau.mill.scalafix.ScalafixModule
import mill.scalalib.ScalaModule
import mill.*
import mill.javalib.TestModule
import mill.scalalib.scalafmt.ScalafmtModule
import mill.scalalib.DepSyntax

trait CommonTestModule extends ScalaModule, TestModule.ScalaTest, ScalafixModule, ScalafmtModule {
  def mvnDeps = Seq(
    mvn"org.scalatestplus::scalacheck-1-19::3.2.20.0",
  )

  override def scalaTestVersion = Task("3.2.20")
}
