package eu.neverblink.linkml.schemaview

import scala.collection.mutable

object Closure {
  def apply[T](
      start: T,
      function: T => Iterable[T],
      reflexive: Boolean = true,
  ): Iterable[T] = {
    val ret = if reflexive then mutable.ArrayBuffer(start) else mutable.ArrayBuffer.empty[T]
    val visited = mutable.ArrayBuffer.empty[T]
    val todo = mutable.ArrayDeque[T](start)

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
