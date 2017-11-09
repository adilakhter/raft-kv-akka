package pl.edu.agh.iosr.raft.model

import play.api.libs.json.{JsString, Writes}

/**
  * Unique Raft participant identifier.
  */
final case class Id(value: Int) extends AnyVal

object Id {
  implicit val format: Writes[Id] = id => JsString(id.value.toString)
}
