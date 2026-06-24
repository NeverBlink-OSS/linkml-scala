package eu.neverblink.linkml.schemaview

import scala.collection.mutable

object Closure {
  def reflexive[T](start: T, function: T => Iterable[T]): Iterable[T] =
    get(Seq(start), function, reflexive = true)

  def reflexive[T](start: Iterable[T], function: T => Iterable[T]): Iterable[T] =
    get(start, function, reflexive = true)

  def irreflexive[T](start: T, function: T => Iterable[T]): Iterable[T] =
    get(Seq(start), function, reflexive = false)

  def irreflexive[T](start: Iterable[T], function: T => Iterable[T]): Iterable[T] =
    get(start, function, reflexive = false)

  def get[T](start: T, function: T => Iterable[T], reflexive: Boolean): Iterable[T] =
    get(Seq(start), function, reflexive)

  def get[T](
      start: Iterable[T],
      function: T => Iterable[T],
      reflexive: Boolean,
  ): Iterable[T] = {
    val ret = if reflexive then mutable.ArrayBuffer.from(start) else mutable.ArrayBuffer.empty[T]
    val visited = mutable.ArrayBuffer.empty[T]
    val todo = mutable.ArrayDeque.from(start)

    while todo.nonEmpty do {
      val current = todo.removeLast()
      visited.append(current)
      for neighbor <- function(current) do {
        if !visited.contains(neighbor) then {
          todo.append(neighbor)
          ret.append(neighbor)
        }
      }
    }

    ret.toSeq
  }
}
