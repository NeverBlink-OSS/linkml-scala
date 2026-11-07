package eu.neverblink.linkml.rdf

/** A growable array of `Int` values, without boxing them. */
final class IntBuffer(initialCapacity: Int = 16) {
  private var values = new Array[Int](math.max(initialCapacity, 1))
  private var size = 0

  def length: Int = size

  def isEmpty: Boolean = size == 0

  def apply(i: Int): Int = values(i)

  def add(value: Int): Unit = {
    if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2)
    values(size) = value
    size += 1
  }

  /** Drops the values after the first `length` ones. */
  def truncate(length: Int): Unit = if (length < size) size = length

  def clear(): Unit = size = 0

  def foreach(f: Int => Unit): Unit = {
    var i = 0
    while (i < size) {
      f(values(i))
      i += 1
    }
  }
}
