package pl.edu.agh.iosr.raft

import akka.actor.{ActorRef, ActorSystem, Props}
import pl.edu.agh.iosr.raft.RaftActor.NodesInitialized

object Simulation extends App {

  import scala.concurrent.duration._

  val Nodes = 1
  val Config = RaftConfig(1.second, 11.seconds)

  val system = ActorSystem("raft-kv-akka")

  val refs: Vector[ActorRef] = (0 until Nodes).map(idx => system.actorOf(Props(new RaftActor(Id(idx), Config))))(collection.breakOut)

  refs.foreach(_ ! NodesInitialized(refs))
}
