package pl.edu.agh.iosr.raft

import akka.actor.{ActorSystem, Props}
import akka.testkit._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import pl.edu.agh.iosr.raft.RaftActor.{GetReport, NodesInitialized}
import pl.edu.agh.iosr.raft.command.SetValue
import pl.edu.agh.iosr.raft.model.{Id, Term}
import pl.edu.agh.iosr.raft.status._

class RaftActorTest extends TestKit(ActorSystem("RaftActorTest"))
  with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures with Eventually {

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  import scala.concurrent.duration._

  val maxElectionTimeout: FiniteDuration = 4.seconds
  val config = RaftConfig(500.millis, 2.seconds, maxElectionTimeout)

  "An RaftActor" must {
    "start uninitialized" in {
      val id = Id(0)
      val actor = system.actorOf(Props(new RaftActor(id, config)))

      actor ! GetReport

      val report = receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport]
      report shouldBe ActorStateReport(id, Uninitialized, Term(0), 0, 0, Map.empty)
    }

    "become a follower after initialization" in {
      val id = Id(0)
      val actor = system.actorOf(Props(new RaftActor(id, config)))

      actor ! NodesInitialized(Vector(actor))
      actor ! GetReport

      val report = receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport]
      report shouldBe ActorStateReport(id, Follower, Term(0), 0, 0, Map.empty)
    }

    "become a leader after max election timeout" in {
      val id = Id(0)
      val actor = system.actorOf(Props(new RaftActor(id, config)))

      actor ! NodesInitialized(Vector(actor))

      Thread.sleep(2 * maxElectionTimeout.toMillis)

      actor ! GetReport

      val report = receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport]
      report shouldBe ActorStateReport(id, Leader, Term(1), 0, 0, Map.empty)
    }

    "apply state change" in {
      val id = Id(0)
      val actor = system.actorOf(Props(new RaftActor(id, config)))

      actor ! NodesInitialized(Vector(actor))

      eventually {
        actor ! GetReport
        receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport].state shouldBe Leader
      }(PatienceConfig(maxElectionTimeout, config.broadcastTime), implicitly)

      val key = "k1"
      val value = "v1"
      actor ! SetValue(key, value)

      eventually {
        actor ! GetReport
        val beforeApply = receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport]
        beforeApply.values shouldBe empty
        beforeApply.commitIndex shouldBe 1
        beforeApply.lastApplied shouldBe 0
      }(PatienceConfig(config.broadcastTime * 3, config.broadcastTime), implicitly)

      eventually {
        actor ! GetReport
        val report = receiveOne(patienceConfig.timeout).asInstanceOf[ActorStateReport]
        report.values shouldBe Map(key -> value)
        report.commitIndex shouldBe 1
        report.lastApplied shouldBe 1
      }(PatienceConfig(config.broadcastTime * 3, config.broadcastTime), implicitly)
    }
  }
}
