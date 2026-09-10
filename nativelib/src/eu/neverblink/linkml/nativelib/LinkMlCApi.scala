package eu.neverblink.linkml.nativelib

import eu.neverblink.linkml.nativelib.LinkMlNativeApi

import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8

import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.*

/** The C ABI, built with Scala Native.
  *
  *   - Options are one JSON string, and may be NULL for defaults.
  *   - Failure is NULL plus a message written to `*error`. Loading returns handle 0 instead.
  *   - Every returned string belongs to the caller and must go back through `linkml_free`.
  */
object LinkMlCApi {

  @exported("linkml_abi_version")
  def abiVersion(): CInt = LinkMlNativeApi.abiVersion

  @exported("linkml_build_info")
  def buildInfo(error: Ptr[CString]): CString =
    write(error, LinkMlNativeApi.buildInfo)

  @exported("linkml_load_file")
  def loadFile(
      path: CString,
      options: CString,
      report: Ptr[CString],
      error: Ptr[CString],
  ): CLongLong = {
    clear(report)
    clear(error)
    try {
      val loaded = LinkMlNativeApi.loadFile(string(path), string(options))
      set(report, loaded.report)
      loaded.handle
    } catch {
      case t: Throwable =>
        set(error, LinkMlNativeApi.describe(t))
        0L
    }
  }

  @exported("linkml_load_string")
  def loadString(
      path: CString,
      schema: CString,
      importNames: Ptr[CString],
      importBodies: Ptr[CString],
      importCount: CInt,
      options: CString,
      report: Ptr[CString],
      error: Ptr[CString],
  ): CLongLong = {
    clear(report)
    clear(error)
    try {
      val loaded = LinkMlNativeApi.loadString(
        string(path),
        string(schema),
        strings(importNames, importCount),
        strings(importBodies, importCount),
        string(options),
      )
      set(report, loaded.report)
      loaded.handle
    } catch {
      case t: Throwable =>
        set(error, LinkMlNativeApi.describe(t))
        0L
    }
  }

  @exported("linkml_close")
  def close(handle: CLongLong): Unit =
    try LinkMlNativeApi.close(handle)
    catch { case _: Throwable => () }

  @exported("linkml_lint")
  def lint(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    document(handle, options, error, LinkMlNativeApi.lint)

  /** NULL-safe, and never throws: a leak beats a crash. */
  @exported("linkml_free")
  def free(buffer: CString): Unit =
    if buffer != null then stdlib.free(buffer)

  /** Correct the stack bounds Scala Native recorded when the library was loaded. Call once, from
    * the thread that loaded it and that will make every call. See the C file for why.
    */
  @exported("linkml_init_threads")
  def initThreads(): CInt = Attach.linkml_init_threads_impl()

  /** Read an Apache Ossie ontology and return the LinkML schema it describes.
    *
    * Takes a document rather than a schema handle, so it is hand-written here rather than generated
    * into `LinkMlCGenerators` with the rest.
    */
  @exported("linkml_from_ossie")
  def fromOssie(ontology: CString, options: CString, error: Ptr[CString]): CString =
    write(error, out => LinkMlNativeApi.fromOssie(string(ontology), string(options), out))

  // Internals

  private[nativelib] def document(
      handle: CLongLong,
      options: CString,
      error: Ptr[CString],
      body: (Long, String, OutputStream) => Unit,
  ): CString =
    write(error, out => body(handle, string(options), out))

  private def write(error: Ptr[CString], body: OutputStream => Unit): CString = {
    clear(error)
    val out = new MallocOutputStream
    try {
      body(out)
      out.take()
    } catch {
      case t: Throwable =>
        // Release the document first: when the failure is "out of memory", the message needs a few
        // bytes of it back.
        out.discard()
        set(error, LinkMlNativeApi.describe(t))
        null
    }
  }

  private def string(ptr: CString): String =
    if ptr == null then null else fromCString(ptr, UTF_8)

  private def strings(ptr: Ptr[CString], count: CInt): Array[String] =
    if count <= 0 then Array.empty
    else if ptr == null then
      throw new IllegalArgumentException(s"import map has $count entries but no array")
    else Array.tabulate(count)(i => string(ptr(i)))

  private def clear(out: Ptr[CString]): Unit =
    if out != null then !out = null

  /** Writes a copy of [[value]] through [[out]]. If even that allocation fails the pointer is left
    * NULL, so a caller has to treat "NULL with no message" as a failure too.
    */
  private def set(out: Ptr[CString], value: String): Unit =
    if out != null then !out = copy(value)

  private def copy(value: String): CString = {
    val bytes = value.getBytes(UTF_8)
    val buffer = stdlib.malloc(bytes.length + 1)
    if buffer == null then null
    else {
      var i = 0
      while i < bytes.length do {
        buffer(i) = bytes(i)
        i += 1
      }
      buffer(bytes.length) = 0.toByte
      buffer
    }
  }
}
