package eu.neverblink.linkml.runtime

object FastUtils {
  extension [A](inline opt: Option[A]) {
    inline def getOrElseFast(inline default: A): A = opt match {
      case Some(a) => a
      case _ => default
    }

    inline def flatMapFast[B](inline f: A => Option[B]): Option[B] = opt match {
      case Some(a) => f(a)
      case _ => None
    }

    inline def foldFast[B](inline ifEmpty: B)(inline f: A => B): B = opt match {
      case Some(a) => f(a)
      case _ => ifEmpty
    }

    inline def foreachFast[B](inline f: A => Unit): Unit = opt match {
      case Some(a) => f(a)
      case _ =>
    }

    inline def mapFast[B](inline f: A => B): Option[B] = opt match {
      case Some(a) => new Some(f(a))
      case _ => None
    }

    inline def orElseFast(inline fallback: Option[A]): Option[A] =
      if (opt eq None) fallback
      else opt

    inline def orNullFast: A = opt match {
      case Some(a) => a
      case _ => null.asInstanceOf[A]
    }
  }
}
