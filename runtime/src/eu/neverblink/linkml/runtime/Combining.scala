package eu.neverblink.linkml.runtime

import scala.annotation.unused
import eu.neverblink.linkml.runtime.FastUtils.*

/** Combine two optional values into one with a handler for combining them if both are defined and
  * are not identical
  *
  * @param combineSome
  *   Handler to combine the values of [[o1]] and [[o2]] if both are defined and distinct
  * @return
  *   The combined optional value
  */
def combineOption[T](o1: Option[T], o2: Option[T], combineSome: (T, T) => T): Option[T] =
  o1.foldFast(o2) { v1 =>
    o2.foldFast(o1) { v2 =>
      if (v1 != v2) new Some(combineSome(v1, v2))
      else o1
    }
  }

/** Combine two [[Seq]]s if they're distinct
  */
def combineSeq[T](v1: Seq[T], v2: Seq[T]): Seq[T] =
  if v2.isEmpty || v1 == v2 then v1 else v1.appendedAll(v2)

/** Returns the first non-empty list.
  *
  * Used for `any_of` and `exactly_one_of` to keep the list of the more specific slot. The other
  * list goes to `all_of`, see [[wrapIfNotSubset]] and [[wrapIfDifferent]].
  */
def combineNonEmpty[T](v1: Seq[T], v2: Seq[T]): Seq[T] =
  if (v1.isEmpty) v2 else v1

/** Returns `v1` followed by the elements of `v2` that are not in `v1`.
  *
  * Used to join the `all_of` and `none_of` lists of two slots, because all members of both lists
  * must be checked.
  */
def combineDistinct[T](v1: Seq[T], v2: Seq[T]): Seq[T] =
  if (v1.isEmpty) v2
  else if (v2.isEmpty) v1
  else {
    val added = v2.filterNot(v1.contains)
    if (added.isEmpty) v1 else v1.appendedAll(added)
  }

/** Returns a list with `wrap(v2)` if both lists are non-empty and `v1` has an element that is not
  * in `v2`, otherwise an empty list.
  *
  * Used to add the other `any_of` list (`v2`) to `all_of`. It is not needed when `v2` has all
  * members of `v1`, because then any value that matches `v1` also matches `v2`.
  */
def wrapIfNotSubset[T](v1: Seq[T], v2: Seq[T], wrap: Seq[T] => T): Seq[T] =
  if (v1.isEmpty || v2.isEmpty || v1.forall(v2.contains)) Nil else wrap(v2) :: Nil

/** Returns a list with `wrap(v2)` if both lists are non-empty and not equal, otherwise an empty
  * list.
  *
  * Used to add the other `exactly_one_of` list of the less specific slot (`v2`) to `all_of`. It can
  * be skipped only when both lists are equal. If `v2` has more members, a value can match two of
  * them: `v2` rejects it, but `v1` may accept it.
  */
def wrapIfDifferent[T](v1: Seq[T], v2: Seq[T], wrap: Seq[T] => T): Seq[T] =
  if (v1.isEmpty || v2.isEmpty || v1 == v2) Nil else wrap(v2) :: Nil

/** Combine two [[Map]]s if they're distinct
  */
def combineMap[T](v1: Map[String, T], v2: Map[String, T]): Map[String, T] =
  if v2.isEmpty || v1 == v2 then v1 else v1.concat(v2)

/** Combine values for the `maximum_value` metaslot
  */
// TODO COMPAT
inline def combineMax(v1: LinkmlAny, @unused v2: LinkmlAny): LinkmlAny = v1

/** Combine values for the `minimum_value` metaslot
  */
// TODO COMPAT
inline def combineMin(v1: LinkmlAny, @unused v2: LinkmlAny): LinkmlAny = v1

/** Combine values for the `pattern` metaslot
  */
// TODO COMPAT
inline def combinePattern(v1: String, @unused v2: String): String = v1

/** Combine boolean values with an OR operation
  */
inline def combineBoolean(v1: Boolean, v2: Boolean): Boolean = v1 || v2

/** Fallback combine function that simply returns the first value
  */
inline def combineFallback[T](v1: T, @unused v2: T): T = v1
