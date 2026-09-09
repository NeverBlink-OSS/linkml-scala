package eu.neverblink.linkml.nativelib

import java.io.OutputStream

import scala.scalanative.libc.{stdlib, string}
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** An [[OutputStream]] writing into malloc'd memory, so a generated document never has to exist as
  * a Scala string before it is handed to C.
  *
  * Not thread-safe. One per call, which is how [[LinkMlCApi]] uses it.
  */
private[nativelib] final class MallocOutputStream extends OutputStream {

  private var buffer: Ptr[Byte] = stdlib.malloc(MallocOutputStream.InitialCapacity)
  private var capacity: Int = if buffer == null then 0 else MallocOutputStream.InitialCapacity
  private var length: Int = 0

  if buffer == null then throw new OutOfMemoryError("could not allocate the output buffer")

  override def write(b: Int): Unit = {
    ensure(1)
    buffer(length) = b.toByte
    length += 1
  }

  override def write(bytes: Array[Byte], offset: Int, count: Int): Unit =
    if count > 0 then {
      ensure(count)
      string.memcpy(buffer + length, bytes.at(offset), count.toCSize)
      length += count
    }

  /** NUL-terminate and hand the buffer to the caller, who then owns it and must `linkml_free` it.
    */
  def take(): CString = {
    ensure(1)
    buffer(length) = 0.toByte
    val result = buffer
    buffer = null
    capacity = 0
    length = 0
    result
  }

  /** Release the buffer. Safe to call twice, and after [[take]]. */
  def discard(): Unit =
    if buffer != null then {
      stdlib.free(buffer)
      buffer = null
      capacity = 0
      length = 0
    }

  private def ensure(extra: Int): Unit = {
    val needed = length + extra
    if needed > capacity then {
      var grown = capacity
      while grown < needed do
        grown =
          if grown < MallocOutputStream.DoublingLimit then grown * 2
          else if grown > MallocOutputStream.MaxCapacity - grown / 2 then
            MallocOutputStream.MaxCapacity
          else grown + grown / 2
      if grown < needed then
        throw new OutOfMemoryError(
          s"the generated document does not fit in ${MallocOutputStream.MaxCapacity} bytes",
        )
      val moved = stdlib.realloc(buffer, grown)
      if moved == null then throw new OutOfMemoryError("could not grow the output buffer")
      buffer = moved
      capacity = grown
    }
  }
}

private[nativelib] object MallocOutputStream {

  /** Matches jsoniter's own buffer, so small documents never grow. */
  private val InitialCapacity = 32 * 1024

  /** Double up to here, then grow by half. */
  private val DoublingLimit = 8 * 1024 * 1024

  private val MaxCapacity = Int.MaxValue - 8
}
