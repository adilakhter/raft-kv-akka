package pl.edu.agh.iosr.raft

import akka.actor.Actor
import akka.remote.testkit.MultiNodeSpecCallbacks
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}

trait BasicMultiNodeSpec extends MultiNodeSpecCallbacks
                          with FlatSpecLike
                          with BeforeAndAfterAll {
  override def beforeAll = multiNodeSpecBeforeAll()

  override def afterAll = multiNodeSpecAfterAll()
}

/**
  * Use for testing.
  *
  * Forwards all messages received to the system's EventStream.
  * Use this to deterministically `awaitForLeaderElection` etc.
  */
trait EventStreamAllMessages {
  this: Actor =>

  override def aroundReceive(receive: Actor.Receive, msg: Any) = {
    context.system.eventStream.publish(msg.asInstanceOf[AnyRef])

    receive.applyOrElse(msg, unhandled)
  }
}
