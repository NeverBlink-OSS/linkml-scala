package eu.neverblink.linkml.generator.rdf

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.io.ByteArrayOutputStream

class FastBufferedOutputStreamSpec extends AnyWordSpec with Matchers {
  class SpyOutputStream extends ByteArrayOutputStream {
    var flushCount = 0
    var closeCount = 0

    override def flush(): Unit = {
      flushCount += 1
      super.flush()
    }

    override def close(): Unit = {
      closeCount += 1
      super.close()
    }
  }

  "A FastBufferedOutputStream" should {
    "write single bytes and hold them in the buffer until flushed" in {
      val spy = new SpyOutputStream()
      val out = new FastBufferedOutputStream(spy)
      out.write('a'.toInt)
      out.write('b'.toInt)
      out.write('c'.toInt)
      spy.toByteArray shouldBe empty
      out.flush()
      spy.toByteArray shouldBe Array('a'.toByte, 'b'.toByte, 'c'.toByte)
    }

    "automatically flush when capacity (32768) is exceeded by single byte writes" in {
      val spy = new SpyOutputStream()
      val out = new FastBufferedOutputStream(spy)
      val capacity = 32768
      for (_ <- 1 to capacity) {
        out.write(1)
      }
      spy.toByteArray shouldBe empty
      out.write(2)
      spy.toByteArray.length shouldBe capacity
      spy.toByteArray.forall(_ == 1) shouldBe true
      out.flush()
      spy.toByteArray.length shouldBe capacity + 1
      spy.toByteArray.last shouldBe 2
    }

    "write a byte array correctly" in {
      val spy = new SpyOutputStream()
      val out = new FastBufferedOutputStream(spy)
      val data = "Hello, ScalaTest!".getBytes
      out.write(data, 0, data.length)
      spy.toByteArray shouldBe empty
      out.flush()
      spy.toByteArray shouldBe data
    }

    "delegate flush() to the underlying stream" in {
      val spy = new SpyOutputStream()
      val out = new FastBufferedOutputStream(spy)
      out.write(Seq(1, 2, 3).map(_.toByte).toArray, 0, 3)
      spy.flushCount shouldBe 0
      out.flush()
      spy.flushCount shouldBe 1
      spy.toByteArray.length shouldBe 3
    }

    "flush the buffer and delegate close() to the underlying stream" in {
      val spy = new SpyOutputStream()
      val out = new FastBufferedOutputStream(spy)
      out.write(42)
      spy.closeCount shouldBe 0
      spy.toByteArray shouldBe empty
      out.close()
      spy.toByteArray shouldBe Array(42.toByte)
      spy.closeCount shouldBe 1
    }
  }
}
