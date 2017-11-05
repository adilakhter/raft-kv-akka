package pl.edu.agh.iosr.raft

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration.FiniteDuration

final case class RaftConfig(broadcastTime: FiniteDuration,
                            private val electionTimeoutMin: FiniteDuration,
                            private val electionTimeoutMax: FiniteDuration
                           ) {
  require(electionTimeoutMax > electionTimeoutMin, s"to ($electionTimeoutMax) must be greater than from ($electionTimeoutMin) in order to create valid election timeout.")

  def randomElectionTimeout(): FiniteDuration = {
    import scala.concurrent.duration._
    (electionTimeoutMin.toMillis + ThreadLocalRandom.current().nextInt(electionTimeoutMax.toMillis.toInt - electionTimeoutMin.toMillis.toInt)).millis
  }
}
