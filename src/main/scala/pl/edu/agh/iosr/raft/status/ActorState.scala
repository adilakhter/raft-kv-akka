package pl.edu.agh.iosr.raft.status

sealed trait ActorState extends Product

case object Uninitialized extends ActorState

case object Follower extends ActorState

case object Candidate extends ActorState

case object Leader extends ActorState
