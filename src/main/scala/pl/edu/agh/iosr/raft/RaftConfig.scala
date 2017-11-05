package pl.edu.agh.iosr.raft

import scala.concurrent.duration.FiniteDuration

final case class RaftConfig(broadcastTime: FiniteDuration, electionTimeout: FiniteDuration) {
  require(10 * broadcastTime < electionTimeout)
}
