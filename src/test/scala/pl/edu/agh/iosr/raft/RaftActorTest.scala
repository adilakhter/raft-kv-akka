package pl.edu.agh.iosr.raft

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import pl.edu.agh.iosr.raft.RaftActor.{GetReport, NodesInitialized}
import pl.edu.agh.iosr.raft.model.{Id, Term}
import pl.edu.agh.iosr.raft.status._

class RaftActorTest extends TestKit(ActorSystem("RaftActorTest"))
  with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures {

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  import scala.concurrent.duration._

  val maxElectionTimeout: FiniteDuration = 4.seconds
  val config = RaftConfig(1.second, 2.seconds, maxElectionTimeout)

  "An RaftActor" must {
    "start uninitialized" in {
      val id = Id(0)
      val actor = system.actorOf(Props(new RaftActor(id, config)))

      actor ! GetReport

      val report = receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport]
      report shouldBe ActorStateReport(id, Uninitialized, Term(0), 0, 0)
    }

    "become a follower after initialization" in {
      val id = Id(0)
      val actor = system.actorOf(Props(new RaftActor(id, config)))

      actor ! NodesInitialized(Vector(actor))
      actor ! GetReport

      val report = receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport]
      report shouldBe ActorStateReport(id, Follower, Term(0), 0, 0)
    }

    "become a leader after max election timeout" in {
      val id = Id(0)
      val actor = system.actorOf(Props(new RaftActor(id, config)))

      actor ! NodesInitialized(Vector(actor))

      Thread.sleep(maxElectionTimeout.toMillis)

      actor ! GetReport

      val report = receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport]
      report shouldBe ActorStateReport(id, Leader, Term(1), 0, 0)
    }
  }
}
