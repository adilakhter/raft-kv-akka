package pl.edu.agh.iosr.raft.model

/**
  * Raft term.
  */
final case class Term(value: Int) extends AnyVal with Ordered[Term] {
  override def compare(that: Term): Int = value.compareTo(that.value)
}
