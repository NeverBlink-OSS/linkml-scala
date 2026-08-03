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
    val ret = mutable.ListBuffer.empty[T]
    val visited = mutable.ArrayBuffer.empty[T]
    val todo = mutable.ArrayDeque.empty[T]
    start.foreach { s =>
      if (!visited.contains(s)) {
        visited.addOne(s)
        todo.addOne(s)
        if (reflexive) ret.addOne(s)
      }
    }
    while (todo.nonEmpty) {
      val current = todo.removeLast()
      function(current).foreach { neighbor =>
        if (!visited.contains(neighbor)) {
          visited.addOne(neighbor)
          todo.addOne(neighbor)
          ret.addOne(neighbor)
        }
      }
    }
    ret.toList
  }
}
