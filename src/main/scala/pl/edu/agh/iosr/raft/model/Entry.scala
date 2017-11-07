package pl.edu.agh.iosr.raft.model

import pl.edu.agh.iosr.raft.command.Command

/**
  * A log entry representing a command and the term of its reception.
  */
final case class Entry(term: Term, command: Command)
