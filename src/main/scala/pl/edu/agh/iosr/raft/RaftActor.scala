package pl.edu.agh.iosr.raft

import akka.actor.{Actor, ActorRef, Cancellable, Props, Stash}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.event.Logging
import akka.http.scaladsl.model.ws.TextMessage
import pl.edu.agh.iosr.raft.command.{Command, Init}
import pl.edu.agh.iosr.raft.model._
import pl.edu.agh.iosr.raft.status._
import play.api.libs.json._

import scala.collection.mutable.ArrayBuffer
import scala.collection.{SeqView, mutable}

class RaftActor(implicit config: RaftConfig) extends Actor with Stash {

  import RaftActor._
  import context._

  private val logger = Logging(context.system, this)

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    def log() = logger.debug("<< {}", msg)
    msg match {
      case SendReport(_) | GetReport(_) =>
      case msg: RaftRpc =>
        sendStatus(msg)
        log()
      case _ => log()
    }
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

  /**
    * current state machine state
    */
  var state: mutable.Map[String, String] = mutable.HashMap.empty

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

  var id: Id = _

  var nodes: Vector[Id] = _

  lazy val otherNodes: SeqView[Id, Seq[_]] = nodes.view(0, id.value) ++ nodes.view(id.value + 1, nodes.size)

  var refsOpt: Option[Vector[ActorRef]] = None

  def target(id: Id): ActorRef = refsOpt.map(_.apply(id.value)).getOrElse(Simulation.RaftRegionRef)

  private def logIndex: Int = log.size - 1

  override def receive: Receive =
    handleStateReport(Uninitialized)
      .orElse {
        case NodesInitialized(id, nodes, refsOpt) =>
          this.id = id
          this.nodes = nodes
          this.refsOpt = refsOpt
          unstashAll()
          logger.info("Becoming a follower (init)")
          become(follower())
        case _ => stash()
      }

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
      log.view(lastApplied + 1, commitIndex + 1).foreach(_.command(state))
      lastApplied = commitIndex
      logger.debug("Last applied: {}", lastApplied)
    }
  }

  private def sendStatus[T: Writes](msg: T): Unit = {
    statusRef.foreach(_ ! TextMessage(Json.toJson(msg).toString()))
  }

  private def stateReport(state: ActorState): ActorStateReport =
    ActorStateReport(id, state, currentTerm, commitIndex, lastApplied, this.state.toMap)

  var statusRef: Option[ActorRef] = None
  private def handleStateReport(state: ActorState): Receive = {
    case StatusRef(_, ref) => statusRef = Some(ref)
    case SendReport(_) => sendStatus(stateReport(state))
    case GetReport(_) => sender() ! stateReport(state)
  }

  private def handleAppendEntries(nomination: Cancellable): Receive = {
    //1. Reply false if term < currentTerm (§5.1)
    //2. Reply false if log doesn’t contain an entry at prevLogIndex whose term matches prevLogTerm (§5.3)
    case AppendEntries(_, term, leaderId, prevLogIndex, prevLogTerm, _, _)
      if term < currentTerm || log.size <= prevLogIndex || log(prevLogIndex).term != prevLogTerm =>
      sender() ! AppendEntriesResult(leaderId, id, currentTerm, logIndex, success = false)
    case AppendEntries(_, term, leaderId, prevLogIndex, _, leaderCommit, entries) =>
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
        val added = maybeConflicting.drop(idx)
        logger.error(added.toString())
        log ++= added
      }

      //4. Append any new entries not already in the log
      log ++= newEntries

      //5. If leaderCommit > commitIndex, set commitIndex = min(leaderCommit, index of last new entry)
      if (leaderCommit > commitIndex) commitIndex = math.min(leaderCommit, log.size - 1)

      sender() ! AppendEntriesResult(leaderId, id, currentTerm, logIndex, success = true)

      become(follower())
  }

  private def handleVotes(nomination: Cancellable): Receive = {
    case RequestVote(_, term, candidateId, lastLogIndex, lastLogTerm) =>
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

      sender() ! RequestVoteResult(candidateId, term, grantVote)
      become(follower())
  }

  private def handleElection(nomination: Cancellable): Receive = {
    case StandForElection =>
      //election timeout elapsed, start election:
      votes = 0
      updateTerm(currentTerm.copy(currentTerm.value + 1)) //increment currentTerm
      self ! RequestVoteResult(id, currentTerm, voteGranted = true) //vote for self
      otherNodes.foreach(nodeId => target(nodeId) ! RequestVote(nodeId, currentTerm, id, logIndex, log.lastOption.map(_.term).getOrElse(currentTerm))) //send RequestVote RPCs to all other servers
      logger.info("Becoming a candidate")
      become(candidate(system.scheduler.scheduleOnce(config.randomElectionTimeout(), self, ElectionTimeout)))
  }

  private def scheduleElection(): Cancellable = {
    logger.debug("Scheduling election")
    system.scheduler.scheduleOnce(config.randomElectionTimeout(), self, StandForElection)
  }

  def follower(nomination: Cancellable = scheduleElection()): Receive =
    handleStateReport(Follower)
      .orElse(handleAppendEntries(nomination))
      .orElse(handleVotes(nomination))
      .orElse(handleElection(nomination))

  def candidate(electionTimeout: Cancellable): Receive =
    handleStateReport(Candidate)
      .orElse(handleAppendEntries(electionTimeout))
      .orElse {
        case ElectionTimeout =>
          logger.debug("Becoming a follower (election timeout)")
          become(follower())
        case RequestVoteResult(_, term, true) if term == currentTerm =>
          votes += 1
          if (votes > nodes.size / 2) {
            otherNodes.foreach(nodeId => target(nodeId) !
              AppendEntries(nodeId, currentTerm, id, logIndex, log.lastOption.map(_.term).getOrElse(currentTerm), commitIndex, Vector.empty)
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
    handleStateReport(Leader)
      .orElse(handleAppendEntries(heartbeat)) //leader can step down
      .orElse {
      case Heartbeat =>
        updateLastApplied()

        nodes.iterator.zip(nextIndex.iterator).foreach {
          case (id, _) if id == this.id =>
          case (id, index) if log.size > index =>
            val rpc = AppendEntries(
              id,
              currentTerm,
              this.id,
              index - 1,
              log(index - 1).term,
              commitIndex,
              log.slice(index, log.size).toVector
            )
            target(id) ! rpc
          case (id, index) =>
            //no new entries
            val rpc = AppendEntries(
              id,
              currentTerm,
              this.id,
              index - 1,
              log(index - 1).term,
              commitIndex,
              Vector.empty
            )
            target(id) ! rpc
        }
        commitIndex = (commitIndex + 1 until log.size).filter { idx =>
          val replicatedEnough = matchIndex.count(_ >= idx) > nodes.size / 2
          val termEqual = log(idx).term == currentTerm
          replicatedEnough && termEqual
        }.lastOption.getOrElse(commitIndex)
        logger.debug("Commit index: {}", commitIndex)
        become(leader())
      case AppendEntriesResult(_, senderId, term, logIndex, success) =>
        heartbeat.cancel()
        val idx = nodes.indexOf(senderId)
        if (success) {
          nextIndex(idx) = logIndex + 1
          matchIndex(idx) = logIndex
        } else {
          nextIndex(idx) = nextIndex(idx) - 1
        }
        become(leader())
      case c: Command =>
        heartbeat.cancel()
        log += Entry(currentTerm, c)
        matchIndex(id.value) = logIndex
        become(leader())
    }
}

object RaftActor {

  final val Name: String = "RaftActor"

  def props(implicit config: RaftConfig): Props = {
    Props(new RaftActor)
  }

  trait ClusterShardedMessage {
    def target: Id

    final def extractShardId: String = target.value.toString

    final def extractEntityId: (String, this.type) = (target.value.toString, this)
  }

  def extractShardId: ExtractShardId = {
    case msg: ClusterShardedMessage => msg.extractShardId
  }

  def extractEntityId: ExtractEntityId = {
    case msg: ClusterShardedMessage => msg.extractEntityId
  }

  sealed trait RaftRpc extends Product

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
  final case class AppendEntries(target: Id, term: Term, leaderId: Id, prevLogIndex: Int, prevLogTerm: Term, leaderCommit: Int, entries: Vector[Entry])
    extends RaftRpc with ClusterShardedMessage

  /**
    * Result of AppendEntriesRPC
    *
    * @param term     currentTerm, for leader to update itself
    * @param logIndex log index after update
    * @param success  true if follower contained entry matching prevLogIndex and prevLogTerm
    */
  final case class AppendEntriesResult(target: Id, sender: Id, term: Term, logIndex: Int, success: Boolean)
    extends RaftRpc with ClusterShardedMessage

  /**
    * Invoked by candidates to gather votes (§5.2).
    *
    * @param term         candidate’s term
    * @param candidateId  candidate requesting vote
    * @param lastLogIndex index of candidate’s last log entry (§5.4)
    * @param lastLogTerm  term of candidate’s last log entry (§5.4)
    */
  final case class RequestVote(target: Id, term: Term, candidateId: Id, lastLogIndex: Int, lastLogTerm: Term)
    extends RaftRpc with ClusterShardedMessage

  /**
    * Result of RequestVoteRPC
    *
    * @param term        currentTerm, for candidate to update itself
    * @param voteGranted true means candidate received vote
    */
  final case class RequestVoteResult(target: Id, term: Term, voteGranted: Boolean)
    extends RaftRpc with ClusterShardedMessage

  object RaftRpc {
    implicit private val appendEntries: Writes[AppendEntries] = Json.writes[AppendEntries]
    implicit private val appendEntriesResult: Writes[AppendEntriesResult] = Json.writes[AppendEntriesResult]
    implicit private val requestVote: Writes[RequestVote] = Json.writes[RequestVote]
    implicit private val requestVoteResult: Writes[RequestVoteResult] = Json.writes[RequestVoteResult]
    implicit val raftRpc: Writes[RaftRpc] = msg => JsObject(Map[String, JsValue](
      "name" -> JsString(msg.productPrefix),
      "value" -> (msg match {
        case msg: AppendEntries => appendEntries.writes(msg)
        case msg: AppendEntriesResult => appendEntriesResult.writes(msg)
        case msg: RequestVote => requestVote.writes(msg)
        case msg: RequestVoteResult => requestVoteResult.writes(msg)
      })))
  }

  /**
    * Starts the algorithm after the nodes are initialized.
    */
  final case class NodesInitialized(target: Id, nodes: Vector[Id], nodeRefs: Option[Vector[ActorRef]] = None) extends ClusterShardedMessage

  /**
    * Requests participant's [[ActorStateReport]].
    */
  final case class GetReport(target: Id) extends ClusterShardedMessage

  /**
    * Requests a status report to current listener reference.
    */
  final case class SendReport(target: Id) extends ClusterShardedMessage

  /**
    * Updates status listener reference.
    */
  final case class StatusRef(target: Id, ref: ActorRef) extends ClusterShardedMessage

  /**
    * Message scheduled by a follower to himself to stand for a new election.
    */
  private case object StandForElection

  /**
    * Message scheduled by a candidate to himself to timeout an election.
    */
  private case object ElectionTimeout

  /**
    * Message scheduled by a leader to himself to periodically sent a heartbeat to other actors.
    */
  private case object Heartbeat

}
