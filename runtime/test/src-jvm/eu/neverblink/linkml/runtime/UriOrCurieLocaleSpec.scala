package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.Locale

/** Only on the JVM, as Scala.js has no `java.util.Locale` */
class UriOrCurieLocaleSpec extends AnyWordSpec, Matchers {
  "UriOrCurie.isCurie" should {
    "find a scheme in upper case with any default locale" in {
      val default = Locale.getDefault
      // In Turkish, the lower case of `I` is a dotless `ı`, so `INFO` would be `ınfo`
      Locale.setDefault(Locale.forLanguageTag("tr"))
      try
        Seq("INFO:x", "SIP:x", "Info:x").foreach(s =>
          withClue(s)(UriOrCurie.isCurie(s) shouldBe false),
        )
      finally Locale.setDefault(default)
    }
  }
}
