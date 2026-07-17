package eu.neverblink.linkml.benchmark

import org.openjdk.jmh.infra.Blackhole

import java.io.OutputStream

object BenchUtil {
  final class BlackholeOutputStream(bh: Blackhole) extends OutputStream {
    override def write(b: Int): Unit = bh.consume(b)
    override def write(b: Array[Byte]): Unit = bh.consume(b)
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      bh.consume(len)
      bh.consume(b)
    }
  }
}
