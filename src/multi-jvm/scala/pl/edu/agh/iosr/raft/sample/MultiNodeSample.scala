package pl.edu.agh.iosr.raft.sample

import akka.actor.Props
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.testkit.ImplicitSender
import pl.edu.agh.iosr.raft.BasicMultiNodeSpec


object MultiNodeSampleConfig extends MultiNodeConfig {
  val node1: RoleName = role("node1")
  val node2: RoleName = role("node2")
}

class MultiNodeSample extends MultiNodeSpec(MultiNodeSampleConfig)
                      with BasicMultiNodeSpec
                      with ImplicitSender {

  import MultiNodeSampleConfig._

  // initial Participants

  def initialParticipants = roles.size

  "A MultiNodeSample" should "wait for all nodes to enter a barrier" in {
    enterBarrier("startup")
  }

  it should "send to and receive from a remote node" in {
    runOn(node2){
      system.actorOf(Props[Worker], name = "worker")

      enterBarrier("deployed")
    }

    runOn(node1){
      enterBarrier("deployed")

      val worker = system.actorSelection(node(node2) / "user" / "worker")

      worker ! Worker.Work

      expectMsg(Worker.Done)
    }

    enterBarrier("finished")
  }

}

class MultiNodeSampleMultiJvmNode1 extends MultiNodeSample
class MultiNodeSampleMultiJvmNode2 extends MultiNodeSample