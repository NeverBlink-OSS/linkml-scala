package eu.neverblink.linkml.generator

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  WriterConfig,
  writeToStream,
  writeToString,
}
import eu.neverblink.linkml.generator.util.{CharSink, StringSink, Utf8ByteSink}

import java.io.OutputStream

/** A generator that produces a single document.
  *
  * [[writeTo]] is the primary form. Implementations buffer internally and flush what they buffered,
  * so a caller should provide an unbuffered file stream to write to. Implementations never close
  * the stream, because the caller may have more to write (and because it might be stdout).
  *
  * [[serialize]] exists for the callers that really do need a complete String in memory - the
  * browser build and most tests. If possible, use [[writeTo]] with a stream instead.
  *
  * @tparam O
  *   the generator's own `Options` type
  */
trait DocumentGenerator[O] {

  /** Options to use when the caller does not pass any. */
  protected def defaultOptions: O

  /** Write the document to [[out]]. Flushes what it buffered; does not close [[out]]. */
  def writeTo(out: OutputStream, options: O = defaultOptions): Unit

  /** The whole document as a string. Materializes it - prefer [[writeTo]] where a stream exists. */
  def serialize(options: O = defaultOptions): String
}

/** A [[DocumentGenerator]] that serializes a model with jsoniter, which writes UTF-8 bytes itself.
  *
  * @tparam D
  *   the intermediate model that [[generate]] builds and the codec knows how to encode
  */
trait JsonDocumentGenerator[O, D] extends DocumentGenerator[O] {

  /** Build the document's model. Public because callers inspect and post-process it. */
  def generate(options: O = defaultOptions): D

  protected def codec: JsonValueCodec[D]

  /** Pretty-printing settings, which some generators expose as an option. */
  protected def writerConfig(options: O): WriterConfig

  final def writeTo(out: OutputStream, options: O = defaultOptions): Unit =
    writeToStream(generate(options), out, writerConfig(options))(using codec)

  final def serialize(options: O = defaultOptions): String =
    writeToString(generate(options), writerConfig(options))(using codec)
}

/** A [[DocumentGenerator]] that emits characters, which is the natural shape for the text formats.
  *
  * The character-to-byte step is [[Utf8ByteSink]], so writing to a stream never builds a string and
  * serializing to a string never builds any bytes.
  */
trait CharDocumentGenerator[O] extends DocumentGenerator[O] {

  /** Write the document into [[sink]]. The only method a subclass has to supply. */
  protected def writeChars(sink: CharSink, options: O): Unit

  final def writeTo(out: OutputStream, options: O = defaultOptions): Unit = {
    val sink = new Utf8ByteSink(out)
    writeChars(sink, options)
    sink.flush()
  }

  final def serialize(options: O = defaultOptions): String = {
    val sink = new StringSink
    writeChars(sink, options)
    sink.result
  }
}
