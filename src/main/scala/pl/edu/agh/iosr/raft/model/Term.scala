package pl.edu.agh.iosr.raft.model

import play.api.libs.json.{JsNumber, Writes}

/**
  * Raft term.
  */
final case class Term(value: Int) extends AnyVal with Ordered[Term] {
  override def compare(that: Term): Int = value.compareTo(that.value)
}

object Term {
  implicit val format: Writes[Term] = term => JsNumber(BigDecimal(term.value))
}