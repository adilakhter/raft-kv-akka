package pl.edu.agh.iosr.raft.status

import pl.edu.agh.iosr.raft.model.{Id, Term}
import play.api.libs.json.{Json, Writes}

/**
  * Actor state report.
  */
case class ActorStateReport(id: Id, state: ActorState, term: Term, commitIndex: Int, lastApplied: Int, values: Map[String, String])

object ActorStateReport {
  implicit val format: Writes[ActorStateReport] = Json.writes[ActorStateReport]
}