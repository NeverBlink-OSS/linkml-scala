package eu.neverblink.linkml.schemaview.buildinfo

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** The kind of build a LinkML-Scala distribution is.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/build-info
  */
sealed abstract class Platform derives Stringify

object Platform {

  /** Running on a Java virtual machine.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  @named("JVM") case object Jvm extends Platform

  /** A GraalVM native image -- the standalone CLI executable or the shared library.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  @named("NATIVE") case object Native extends Platform

  /** Compiled to JavaScript with Scala.js, running under Node.js or in a browser.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/build-info
    */
  @named("JS") case object Js extends Platform
}
