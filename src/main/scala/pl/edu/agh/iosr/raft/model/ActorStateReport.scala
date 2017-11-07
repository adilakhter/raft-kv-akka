package pl.edu.agh.iosr.raft.model

/**
  * Actor state report.
  */
case class ActorStateReport(id: Id, state: ActorState, term: Term, commitIndex: Int, lastApplied: Int)
