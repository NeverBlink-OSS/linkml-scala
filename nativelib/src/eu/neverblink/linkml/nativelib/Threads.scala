package eu.neverblink.linkml.nativelib

import scala.scalanative.unsafe.*

/** See nativelib/resources/scala-native/linkml_attach.c. */
@extern
private[nativelib] object Attach {
  def linkml_init_threads_impl(): CInt = extern
}
