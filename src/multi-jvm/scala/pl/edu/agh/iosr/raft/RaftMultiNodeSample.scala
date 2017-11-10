package pl.edu.agh.iosr.raft

import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.testkit.ImplicitSender
import akka.util.Timeout
import org.scalatest.Matchers
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import pl.edu.agh.iosr.raft.RaftActor.{GetReport, NodesInitialized}
import pl.edu.agh.iosr.raft.command.SetValue
import pl.edu.agh.iosr.raft.model.Id
import pl.edu.agh.iosr.raft.status.{ActorStateReport, Follower, Leader}


object RaftMultiNodeSampleConfig extends MultiNodeConfig {
  val node1: RoleName = role("node1")
  val node2: RoleName = role("node2")
  val node3: RoleName = role("node3")
}

class RaftMultiNodeSample
  extends MultiNodeSpec(RaftMultiNodeSampleConfig)
    with BasicMultiNodeSpec
    with Matchers
    with ImplicitSender
    with ScalaFutures
    with Eventually {

  import RaftMultiNodeSampleConfig._

  def initialParticipants = roles.size

  import scala.concurrent.duration._

  implicit val timeout: Timeout = Timeout(5.seconds)
  val maxElectionTimeout: FiniteDuration = 4.seconds
  implicit val config: RaftConfig = RaftConfig(500.millis, 2.seconds, maxElectionTimeout)
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 500.millis)


  "A RaftMultiNodeSample" should "wait for all nodes to enter a barrier" in {
    enterBarrier("startup")
  }

  it should "start followers, elect a single leader, propagate values" in {

    runOn(node1) {
      system.actorOf(RaftActor.props, name = "raft1")
      enterBarrier("deployed")
    }

    runOn(node2) {
      system.actorOf(RaftActor.props, name = "raft2")
      enterBarrier("deployed")
    }

    runOn(node3) {
      system.actorOf(RaftActor.props, name = "raft3")
      enterBarrier("deployed")
    }

    val raft1 = system.actorSelection(node(node1) / "user" / "raft1").resolveOne().futureValue
    val raft2 = system.actorSelection(node(node2) / "user" / "raft2").resolveOne().futureValue
    val raft3 = system.actorSelection(node(node3) / "user" / "raft3").resolveOne().futureValue
    val refs = Vector(raft1, raft2, raft3)
    val ids = refs.zipWithIndex.map { case (_, idx) => Id(idx) }
    val refsWithIds = refs.zip(ids)

    enterBarrier("refsReady")

    runOn(node2) {
      enterBarrier("aboutToDrop")
    }

    runOn(node3) {
      enterBarrier("aboutToDrop")
    }

    runOn(node1) {
      refsWithIds.foreach { case (ref, id) => ref ! NodesInitialized(id, ids, Some(refs)) }
      refsWithIds.foreach { case (ref, id) => ref ! GetReport(id) }
      receiveN(ids.size, patienceConfig.timeout).asInstanceOf[Seq[ActorStateReport]].foreach(_.state shouldBe Follower)

      //all followers

      var leaderId: Id = Id(-1)
      eventually {
        refsWithIds.foreach { case (ref, id) => ref ! GetReport(id) }
        val reports = receiveN(ids.size, patienceConfig.timeout).asInstanceOf[Seq[ActorStateReport]]
        val states = reports.map(_.state)
        states.count(_ == Leader) shouldBe 1
        states.count(_ == Follower) shouldBe ids.size - 1
        leaderId = reports.find(_.state == Leader).get.id
      }

      //elected leader

      val (leaderRef, _) = refsWithIds.apply(leaderId.value)
      val (k1, v1) = ("k1", "v1")
      val (k2, v2) = ("k2", "v2")
      leaderRef ! SetValue(leaderId, k1, v1)
      leaderRef ! SetValue(leaderId, k2, v2)

      eventually {
        refsWithIds.foreach { case (ref, id) => ref ! GetReport(id) }
        val reports = receiveN(ids.size, patienceConfig.timeout).asInstanceOf[Seq[ActorStateReport]]
        reports.foreach(_.values shouldBe Map(k1 -> v1, k2 -> v2))
        reports.foreach(_.commitIndex shouldBe 2)
        reports.foreach(_.lastApplied shouldBe 2)
      }

      enterBarrier("aboutToDrop")

      testConductor.exit(testConductor.getNodes.futureValue.drop(leaderId.value).head, 0).futureValue
    }

    enterBarrier("leaderDropped")

    eventually {
      refsWithIds.foreach { case (ref, id) => ref ! GetReport(id) }
      val reports = receiveN(ids.size - 1, patienceConfig.timeout).asInstanceOf[Seq[ActorStateReport]]
      val states = reports.map(_.state)
      states.count(_ == Leader) shouldBe 1
      states.count(_ == Follower) shouldBe ids.size - 2 //one down, one leader
    }
  }
}

class RaftMultiNodeSampleMultiJvmNode1 extends RaftMultiNodeSample

class RaftMultiNodeSampleMultiJvmNode2 extends RaftMultiNodeSample

class RaftMultiNodeSampleMultiJvmNode3 extends RaftMultiNodeSample