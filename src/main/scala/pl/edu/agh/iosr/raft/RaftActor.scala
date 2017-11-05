package pl.edu.agh.iosr.raft

import akka.actor.{Actor, ActorRef, Cancellable, Stash}
import akka.event.Logging

import scala.collection.mutable.ArrayBuffer
import scala.collection.{SeqView, mutable}

class RaftActor(id: Id, config: RaftConfig) extends Actor with Stash {

  private val logger = Logging(context.system, this)

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    logger.debug("<< {}", msg)
    receive.applyOrElse(msg, unhandled)
  }

  //persistent state on all
  /**
    * latest term server has seen
    */
  var currentTerm = Term(0)

  /**
    * candidateId that received vote in current term
    */
  var votedFor: Option[Id] = None

  /**
    * log entries; each entry contains command for state machine,
    * and term when entry was received by leader (first index is 1)
    */
  val log: ArrayBuffer[Entry] = mutable.ArrayBuffer(Entry(Term(1), Init))

  //volatile state on all
  /**
    * index of the highest log entry known to be committed (initialized to 0, increases monotonically)
    */
  var commitIndex: Int = 0

  /**
    * index of the highest log entry applied to the state machine (initialized to 0, increases monotonically)
    */
  var lastApplied: Int = 0

  //volatile state on leaders
  /**
    * for each server, index of the next log entry to send to that server (initialized to leader last log index + 1)
    */
  lazy val nextIndex: ArrayBuffer[Int] = Stream.fill(nodes.size)(1).to[ArrayBuffer]

  /**
    * for each server, index of highest log entry known to be replicated on server (initialized to 0, increases monotonically)
    */
  lazy val matchIndex: ArrayBuffer[Int] = Stream.fill(nodes.size)(0).to[ArrayBuffer]

  var votes = 0

  import RaftActor._
  import context._

  override def receive: Receive = {
    case NodesInitialized(nodes) =>
      this.nodes = nodes
      unstashAll()
      logger.info("Becoming a follower (init)")
      become(follower())
    case _ => stash()
  }

  var nodes: Vector[ActorRef] = _

  lazy val otherNodes: SeqView[ActorRef, Seq[_]] = nodes.view(0, id.value) ++ nodes.view(id.value + 1, nodes.size)

  private def logIndex: Int = log.size - 1

  private def updateTerm(term: Term): Unit = {
    if (term > currentTerm) {
      votedFor = None
      currentTerm = term
    }
  }

  /**
    * If commitIndex > lastApplied: increment lastApplied, apply log[lastApplied] to state machine (§5.3)
    */
  private def updateLastApplied(): Unit = {
    if (commitIndex > lastApplied) {
      //todo apply to state machine
      lastApplied = commitIndex
      logger.debug("Last applied: {}", lastApplied)
    }
  }

  private def handleAppendEntries(nomination: Cancellable): Receive = {
    //1. Reply false if term < currentTerm (§5.1)
    //2. Reply false if log doesn’t contain an entry at prevLogIndex whose term matches prevLogTerm (§5.3)
    case AppendEntries(term, _, prevLogIndex, prevLogTerm, _, _)
      if term < currentTerm || log.size <= prevLogIndex || log(prevLogIndex).term != prevLogTerm =>
      sender() ! AppendEntriesResult(currentTerm, logIndex, success = false)
    case AppendEntries(term, _, prevLogIndex, _, leaderCommit, entries) =>
      nomination.cancel()

      updateTerm(term)
      updateLastApplied()

      //3. If an existing entry conflicts with a new one (same index but different terms),
      //   delete the existing entry and all that follow it (§5.3)
      val existing = log.slice(prevLogIndex + 1, log.size)
      val (maybeConflicting, newEntries) = entries.splitAt(existing.size)
      val conflictIdx = Option(
        existing
          .zip(maybeConflicting)
          .indexWhere {
            case (current, incoming) => current.term != incoming.term
          }
      ).filter(_ != -1)
      conflictIdx.foreach { idx =>
        log.reduceToSize(prevLogIndex + idx)
        log ++= maybeConflicting.drop(idx)
      }

      //4. Append any new entries not already in the log
      log ++= newEntries

      //5. If leaderCommit > commitIndex, set commitIndex = min(leaderCommit, index of last new entry)
      if (leaderCommit > commitIndex) commitIndex = math.min(leaderCommit, log.size - 1)

      sender() ! AppendEntriesResult(currentTerm, logIndex, success = true)

      become(follower())
  }

  private def handleVotes(nomination: Cancellable): Receive = {
    case RequestVote(term, candidateId, lastLogIndex, lastLogTerm) =>
      //1. Reply false if term < currentTerm (§5.1)
      //2. If votedFor is null or candidateId, and candidate’s log is
      //   at least as up-to-date as receiver’s log, grant vote (§5.2, §5.4)
      nomination.cancel()
      val grantVote: Boolean =
        term >= currentTerm &&
          votedFor.forall(_ == candidateId) &&
          lastLogIndex >= logIndex &&
          lastLogTerm >= log.last.term

      if (grantVote) votedFor = Some(candidateId)

      sender() ! RequestVoteResult(term, grantVote)
      become(follower())
  }

  private def handleElection(nomination: Cancellable): Receive = {
    case StandForElection =>
      //election timeout elapsed, start election:
      votes = 0
      updateTerm(currentTerm.copy(currentTerm.value + 1)) //increment currentTerm
      self ! RequestVoteResult(currentTerm, voteGranted = true) //vote for self
      otherNodes.foreach(_ ! RequestVote(currentTerm, id, logIndex, log.lastOption.map(_.term).getOrElse(currentTerm))) //send RequestVote RPCs to all other servers
      logger.info("Becoming a candidate")
      become(candidate(system.scheduler.scheduleOnce(config.randomElectionTimeout(), self, ElectionTimeout)))
  }

  private def scheduleElection(): Cancellable = {
    logger.debug("Scheduling election")
    system.scheduler.scheduleOnce(config.randomElectionTimeout(), self, StandForElection)
  }

  def follower(nomination: Cancellable = scheduleElection()): Receive =
    handleAppendEntries(nomination)
      .orElse(handleVotes(nomination))
      .orElse(handleElection(nomination))

  def candidate(electionTimeout: Cancellable): Receive =
    handleAppendEntries(electionTimeout)
      .orElse {
        case ElectionTimeout =>
          logger.debug("Becoming a follower (election timeout)")
          become(follower())
        case RequestVoteResult(term, true) if term == currentTerm =>
          votes += 1
          if (votes > nodes.size / 2) {
            otherNodes.foreach(_ !
              AppendEntries(currentTerm, id, logIndex, log.lastOption.map(_.term).getOrElse(currentTerm), commitIndex, Vector.empty)
            ) //send initial empty AppendEntries RPCs
            nodes.indices.foreach { idx =>
              nextIndex.update(idx, log.size)
              matchIndex.update(idx, 0)
            }
            logger.info("Becoming a leader")
            become(leader())
          }
      }

  private def scheduleHeartbeat(): Cancellable = {
    logger.debug("Scheduling heartbeat")
    system.scheduler.scheduleOnce(config.broadcastTime, self, Heartbeat)
  }

  def leader(heartbeat: Cancellable = scheduleHeartbeat()): Receive =
    handleAppendEntries(heartbeat) //leader can step down
      .orElse {
      case Heartbeat =>
        updateLastApplied()

        nodes.iterator.zip(nextIndex.iterator).foreach {
          case (ref, _) if ref == self =>
          case (ref, index) if log.size - 1 > index =>
            val rpc = AppendEntries(
              currentTerm,
              id,
              index,
              log(index).term,
              commitIndex,
              log.slice(index + 1, log.size).toVector
            )
            ref ! rpc
          case (ref, index) =>
            //no new entries
            val rpc = AppendEntries(
              currentTerm,
              id,
              index - 1,
              log(index - 1).term,
              commitIndex,
              Vector.empty
            )
            ref ! rpc
        }
        commitIndex = (commitIndex + 1 until log.size).filter { idx =>
          val replicatedEnough = matchIndex.count(_ >= idx) > nodes.size / 2
          val termEqual = log(idx).term == currentTerm
          replicatedEnough && termEqual
        }.lastOption.getOrElse(commitIndex)
        logger.debug("Commit index: {}", commitIndex)
        become(leader())
      case AppendEntriesResult(term, logIndex, success) =>
        heartbeat.cancel()
        val idx = nodes.indexOf(sender())
        if (success) {
          nextIndex(idx) = logIndex + 1
          matchIndex(idx) = logIndex
        } else {
          nextIndex(idx) = nextIndex(idx) - 1
        }
        become(leader())
      case c: Command =>
        log += Entry(currentTerm, c)
        matchIndex(id.value) = logIndex
        become(leader())
    }
}

final case class Id(value: Int) extends AnyVal

final case class Term(value: Int) extends AnyVal with Ordered[Term] {
  override def compare(that: Term): Int = value.compareTo(that.value)
}

sealed trait Command

final case class SetValue(key: String, value: String) extends Command

case object Init extends Command

final case class Entry(term: Term, command: Command)


object RaftActor {

  /**
    * Invoked by leader to replicate log entries (§5.3); also used as a heartbeat (§5.2)
    *
    * @param term         leader’s term
    * @param leaderId     so follower can redirect clients
    * @param prevLogIndex index of log entry immediately preceding new ones
    * @param prevLogTerm  term of prevLogIndex entry
    * @param leaderCommit leader’s commitIndex
    * @param entries      log entries to store (empty for heartbeat; may send more than one for efficiency)
    */
  final case class AppendEntries(term: Term, leaderId: Id, prevLogIndex: Int, prevLogTerm: Term, leaderCommit: Int, entries: Vector[Entry])

  /**
    * Result of AppendEntriesRPC
    *
    * @param term     currentTerm, for leader to update itself
    * @param logIndex log index after update
    * @param success  true if follower contained entry matching prevLogIndex and prevLogTerm
    */
  final case class AppendEntriesResult(term: Term, logIndex: Int, success: Boolean)

  /**
    * Invoked by candidates to gather votes (§5.2).
    *
    * @param term         candidate’s term
    * @param candidateId  candidate requesting vote
    * @param lastLogIndex index of candidate’s last log entry (§5.4)
    * @param lastLogTerm  term of candidate’s last log entry (§5.4)
    */
  final case class RequestVote(term: Term, candidateId: Id, lastLogIndex: Int, lastLogTerm: Term)

  /**
    * Result of RequestVoteRPC
    *
    * @param term        currentTerm, for candidate to update itself
    * @param voteGranted true means candidate received vote
    */
  final case class RequestVoteResult(term: Term, voteGranted: Boolean)

  final case class NodesInitialized(nodes: Vector[ActorRef])

  private case object StandForElection

  private case object ElectionTimeout

  private case object Heartbeat

}
