package pl.edu.agh.iosr.raft.status

import play.api.libs.json.{JsString, Writes}

sealed trait ActorState extends Product

case object Uninitialized extends ActorState

case object Follower extends ActorState

case object Candidate extends ActorState

case object Leader extends ActorState

object ActorState {
  implicit val format: Writes[ActorState] = state => JsString(state.productPrefix)
}
