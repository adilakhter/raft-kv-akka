package pl.edu.agh.iosr.raft

import akka.actor.{ActorRef, ActorSystem, Props}
import pl.edu.agh.iosr.raft.RaftActor.NodesInitialized
import pl.edu.agh.iosr.raft.commands.SetValue
import pl.edu.agh.iosr.raft.model.Id

object Simulation extends App {

  import scala.concurrent.duration._

  val Nodes = 5
  val Config = RaftConfig(2.second, 5.seconds, 5.seconds.plus(200.millis))

  val system = ActorSystem("raft-kv-akka")

  val refs: Vector[ActorRef] = (0 until Nodes).map(idx => system.actorOf(Props(new RaftActor(Id(idx), Config))))(collection.breakOut)

  refs.foreach(_ ! NodesInitialized(refs))

  Thread.sleep(20.seconds.toMillis)

  refs.foreach(_ ! SetValue("lol", "abc"))

  Thread.sleep(20.seconds.toMillis)

  refs.foreach(_ ! SetValue("lol2", "abc"))
  refs.foreach(_ ! SetValue("lol3", "abc"))
}
