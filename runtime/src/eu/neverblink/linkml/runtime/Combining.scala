package eu.neverblink.linkml.runtime

import scala.annotation.unused

/** Combine two optional values into one with a handler for combining them if both are defined and
  * are not identical
  *
  * @param combineSome
  *   Handler to combine the values of [[o1]] and [[o2]] if both are defined and distinct
  * @return
  *   The combined optional value
  */
def combineOption[T](o1: Option[T], o2: Option[T], combineSome: (T, T) => T): Option[T] =
  o1 match {
    case Some(v1) =>
      o2 match {
        case Some(v2) if v1 != v2 => new Some(combineSome(v1, v2))
        case _ => o1
      }
    case _ => o2
  }

/** Combine two [[Seq]]s if they're distinct
  */
def combineSeq[T](v1: Seq[T], v2: Seq[T]): Seq[T] = if v1 == v2 then v1 else v1 ++ v2

/** Combine two [[Map]]s if they're distinct
  */
def combineMap[T](v1: Map[String, T], v2: Map[String, T]): Map[String, T] =
  if v1 == v2 then v1 else v1 ++ v2

/** Combine values for the `maximum_value` metaslot
  */
// TODO COMPAT
def combineMax(v1: LinkmlAny, @unused v2: LinkmlAny): LinkmlAny = v1

/** Combine values for the `minimum_value` metaslot
  */
// TODO COMPAT
def combineMin(v1: LinkmlAny, @unused v2: LinkmlAny): LinkmlAny = v1

/** Combine values for the `pattern` metaslot
  */
// TODO COMPAT
def combinePattern(v1: String, @unused v2: String): String = v1

/** Combine boolean values with an OR operation
  */
def combineBoolean(v1: Boolean, v2: Boolean): Boolean = v1 || v2

/** Fallback combine function that simply returns the first value
  */
def combineFallback[T](v1: T, @unused v2: T): T = v1
