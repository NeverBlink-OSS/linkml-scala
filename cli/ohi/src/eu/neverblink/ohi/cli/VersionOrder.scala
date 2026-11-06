package eu.neverblink.ohi.cli

/** Orders versions from the left by runs of digits, compared as numbers, and runs of other
  * characters, compared ignoring case.
  */
private[cli] object VersionOrder extends Ordering[String] {
  private val Run = "[0-9]+|[^0-9]+".r

  override def compare(a: String, b: String): Int = {
    val left = Run.findAllIn(a).toSeq
    val right = Run.findAllIn(b).toSeq
    left.lazyZip(right).map(runs).find(_ != 0).getOrElse(left.size.compare(right.size))
  }

  private def runs(a: String, b: String): Int = (digits(a), digits(b)) match {
    case (true, true) => BigInt(a).compare(BigInt(b))
    case (true, false) => -1
    case (false, true) => 1
    case _ => a.compareToIgnoreCase(b)
  }

  private def digits(run: String): Boolean = run.head >= '0' && run.head <= '9'
}
