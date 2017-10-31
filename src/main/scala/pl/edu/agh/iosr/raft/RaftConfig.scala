package pl.edu.agh.iosr.raft

import scala.concurrent.duration.FiniteDuration

final case class RaftConfig(electionTimeout: FiniteDuration)
