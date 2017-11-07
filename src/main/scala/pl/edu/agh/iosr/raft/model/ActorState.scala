package pl.edu.agh.iosr.raft.model

sealed trait ActorState

case object Candidate extends ActorState

case object Leader extends ActorState

case object Follower extends ActorState
