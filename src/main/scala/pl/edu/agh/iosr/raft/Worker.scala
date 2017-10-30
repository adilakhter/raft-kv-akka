package pl.edu.agh.iosr.raft

import akka.actor.Actor

class Worker extends Actor {
  import Worker._

  def receive = {
    case Work =>
      println(s"I received Work Message and My ActorRef: ${self}")
      sender() ! Done
  }
}

object Worker {
  case object Work
  case object Done 
}
