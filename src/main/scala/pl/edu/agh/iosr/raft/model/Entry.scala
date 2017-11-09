package pl.edu.agh.iosr.raft.model

import pl.edu.agh.iosr.raft.command.Command
import play.api.libs.json.{Json, Writes}

/**
  * A log entry representing a command and the term of its reception.
  */
final case class Entry(term: Term, command: Command)

object Entry {
  implicit val format: Writes[Entry] = Json.writes[Entry]
}
