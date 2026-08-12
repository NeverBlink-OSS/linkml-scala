package eu.neverblink.linkml.runtime

/** A collection of high-performance, macro-expanded extension methods for `Option`.
  *
  * The semantics of these functions are identical to the standard `Option` methods (e.g., `mapFast`
  * does exactly what `map` does, `getOrElseFast` exactly mirrors `getOrElse`), but they are
  * appended with the `Fast` suffix to indicate their zero-allocation nature.
  *
  * By leveraging Scala 3's `inline` keyword, these methods expand directly at the call site at
  * compile time. This eliminates the runtime overhead of lambda creation, closure allocation, and
  * virtual method dispatch.
  *
  * Limitations and usage differences compared to the standard `Option` methods:
  *
  *   1. **Cannot be passed as values:** Because these are `inline def`s, they exist only at compile
  *      time. You cannot partially apply them or pass them around as first-class function values
  *      (e.g., `val f = opt.mapFast _` will fail to compile).
  *   2. **Bytecode Bloat:** Overusing these methods in non-critical (cold) paths will needlessly
  *      increase the size of your compiled bytecode.
  */
object FastUtils {

  extension [A](inline opt: Option[A]) {

    /** Retrieves the option's value if present, otherwise evaluates the fallback value.
      *
      * Both the option and the default value are inlined, ensuring no by-name parameter closures
      * are allocated.
      *
      * @param default
      *   The fallback value to return if the option is empty.
      * @return
      *   The value of the option if it is `Some`, otherwise `default`.
      */
    inline def getOrElseFast(inline default: A): A = opt match {
      case Some(a) => a
      case _ => default
    }

    /** Applies a function to the value inside the option and returns the resulting option.
      *
      * The mapping function `f` is inlined, preventing the allocation of a `Function1` object.
      *
      * @tparam B
      *   The type of the value in the returned option.
      * @param f
      *   The transformation function returning a new `Option`.
      * @return
      *   An `Option` containing the result of applying `f`, or `None` if this is empty.
      */
    inline def flatMapFast[B](inline f: A => Option[B]): Option[B] = opt match {
      case Some(a) => f(a)
      case _ => None
    }

    /** Applies a fallback value if the option is empty, or a mapping function if it contains a
      * value.
      *
      * This is a zero-allocation alternative to `opt.map(f).getOrElse(ifEmpty)`. Both the empty
      * case and the transformation function are inlined directly into the call site.
      *
      * @tparam B
      *   The return type of the fold operation.
      * @param ifEmpty
      *   The value to return if the option is `None`.
      * @param f
      *   The transformation function to apply if the option is `Some`.
      * @return
      *   The result of applying `f` to the value, or `ifEmpty`.
      */
    inline def foldFast[B](inline ifEmpty: B)(inline f: A => B): B = opt match {
      case Some(a) => f(a)
      case _ => ifEmpty
    }

    /** Executes a side-effecting function on the value if the option is not empty.
      *
      * The effect function `f` is inlined, making this equivalent to writing a manual `if`
      * statement or pattern match without the function allocation overhead.
      *
      * @param f
      *   The side-effecting procedure to apply.
      */
    inline def foreachFast[B](inline f: A => Unit): Unit = opt match {
      case Some(a) => f(a)
      case _ =>
    }

    /** Transforms the value inside the option using the provided function.
      *
      * In addition to inlining the mapping function, this implementation uses `new Some(...)`
      * directly instead of `Some.apply(...)`, saving a microscopic amount of method dispatch
      * overhead.
      *
      * @tparam B
      *   The type of the newly mapped value.
      * @param f
      *   The transformation function to apply.
      * @return
      *   A new `Option` containing the mapped value, or `None` if originally empty.
      */
    inline def mapFast[B](inline f: A => B): Option[B] = opt match {
      case Some(a) => new Some(f(a))
      case _ => None
    }

    /** Returns this option if it is non-empty, otherwise evaluates and returns the fallback option.
      *
      * This uses extremely fast reference equality (`eq None`) to check for emptiness rather than
      * standard pattern matching, minimizing byte code and execution time.
      *
      * @param fallback
      *   The alternative option to return if this one is empty.
      * @return
      *   This option if it has a value, otherwise the `fallback` option.
      */
    inline def orElseFast(inline fallback: Option[A]): Option[A] =
      if (opt eq None) fallback
      else opt

    /** Extracts the value from the option, or returns `null` if the option is empty.
      *
      * Useful for Java interoperability or extremely low-level zero-allocation logic. The `null` is
      * explicitly cast to `A` to satisfy the type checker.
      *
      * @return
      *   The underlying value if present, or `null`.
      */
    inline def orNullFast: A = opt match {
      case Some(a) => a
      case _ => null.asInstanceOf[A]
    }
  }
}
