// Copied verbatim from Scala Native 0.5.12 (BSD 3-Clause, (c) EPFL) with one change, marked below.
//
// Scala Native's linker takes the first classpath entry that defines a symbol, and our own classes
// come before the resolved jars, so this file replaces the one in nativelib. Delete it, and the
// directory, once we build against 0.5.13 or later.
//
// Original:
// https://github.com/scala-native/scala-native/blob/v0.5.12/nativelib/src/main/scala/scala/scalanative/meta/LinktimeInfo.scala

package scala.scalanative.meta

import scala.scalanative.unsafe._

/** Constants resolved at link-time from NativeConfig, can be conditionally discard some parts of
  * NIR instructions when linking
  */
object LinktimeInfo {

  @resolvedAtLinktime("scala.scalanative.meta.linktimeinfo.debugMode")
  def debugMode: Boolean = resolved

  @resolvedAtLinktime("scala.scalanative.meta.linktimeinfo.releaseMode")
  def releaseMode: Boolean = resolved

  @resolvedAtLinktime
  def isWindows: Boolean = target.os == "windows"

  @resolvedAtLinktime
  def isLinux: Boolean = target.os == "linux"

  @resolvedAtLinktime
  def isMac: Boolean =
    target.vendor == "apple" &&
      (target.os == "darwin" || target.os == "macosx")

  @resolvedAtLinktime
  def isFreeBSD: Boolean = target.os == "freebsd"

  @resolvedAtLinktime
  def isOpenBSD: Boolean = target.os == "openbsd"

  @resolvedAtLinktime
  def isNetBSD: Boolean = target.os == "netbsd"

  @resolvedAtLinktime("scala.scalanative.meta.linktimeinfo.is32BitPlatform")
  def is32BitPlatform: Boolean = resolved

  @resolvedAtLinktime("scala.scalanative.meta.linktimeinfo.enabledSanitizer")
  def enabledSanitizer: String = resolved

  @resolvedAtLinktime()
  def asanEnabled: Boolean = enabledSanitizer == "address"

  @resolvedAtLinktime(
    "scala.scalanative.meta.linktimeinfo.isWeakReferenceSupported",
  )
  def isWeakReferenceSupported: Boolean = resolved

  // THE CHANGE. Upstream returns true for any 64-bit Unix, but delimcc.c only implements x86-64,
  // i386 and aarch64, so on anything else Scala Native turns on a feature it has no code for and
  // the build dies on `#error "Unsupported platform"`. That is what stops the sdist installing on
  // riscv64. Fixed upstream in scala-native#4937, which landed three days after 0.5.12 was tagged.
  //
  // We return false outright rather than copying upstream's architecture list: we use neither
  // continuations nor virtual threads, and false is the same on every platform we ship, so every
  // wheel build exercises it. An architecture list would only differ on platforms our CI never
  // builds, leaving the interesting case untested.
  @resolvedAtLinktime()
  def isContinuationsSupported: Boolean = false

  @resolvedAtLinktime()
  def isVirtualThreadsSupported: Boolean =
    isMultithreadingEnabled && isContinuationsSupported

  @resolvedAtLinktime(
    "scala.scalanative.meta.linktimeinfo.isMultithreadingEnabled",
  )
  def isMultithreadingEnabled: Boolean = resolved

  // Referenced in nscplugin and codegen
  @resolvedAtLinktime(
    "scala.scalanative.meta.linktimeinfo.contendedPaddingWidth",
  )
  def contendedPaddingWidth: Int = resolved

  @resolvedAtLinktime(
    "scala.scalanative.meta.linktimeinfo.runtimeVersion",
  )
  def runtimeVersion: String = resolved

  @resolvedAtLinktime(
    "scala.scalanative.meta.linktimeinfo.garbageCollector",
  )
  def garbageCollector: String = resolved
  object gc {
    @resolvedAtLinktime def isBoehm: Boolean = garbageCollector == "boehm"
    @resolvedAtLinktime def isImmix: Boolean = garbageCollector == "immix"
    @resolvedAtLinktime def isCommix: Boolean = garbageCollector == "commix"
    @resolvedAtLinktime def isNone: Boolean = garbageCollector == "none"
  }

  object target {
    @resolvedAtLinktime("scala.scalanative.meta.linktimeinfo.target.arch")
    def arch: String = resolved
    @resolvedAtLinktime("scala.scalanative.meta.linktimeinfo.target.vendor")
    def vendor: String = resolved
    @resolvedAtLinktime("scala.scalanative.meta.linktimeinfo.target.os")
    def os: String = resolved
    @resolvedAtLinktime("scala.scalanative.meta.linktimeinfo.target.env")
    def env: String = resolved
  }

  object sourceLevelDebuging {
    @resolvedAtLinktime(
      "scala.scalanative.meta.linktimeinfo.debugMetadata.enabled",
    )
    def enabled: Boolean = resolved

    @resolvedAtLinktime(
      "scala.scalanative.meta.linktimeinfo.debugMetadata.generateFunctionSourcePositions",
    )
    def generateFunctionSourcePositions: Boolean = resolved

  }
}
