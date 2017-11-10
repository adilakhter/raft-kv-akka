package pl.edu.agh.iosr.raft

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import pl.edu.agh.iosr.raft.RaftActor.{NodesInitialized, SendReport, StatusRef}
import pl.edu.agh.iosr.raft.command.{RemoveValue, SetValue}
import pl.edu.agh.iosr.raft.model.Id

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.Random

object Simulation {

  import scala.concurrent.duration._

  implicit private val Config: RaftConfig = RaftConfig(2.second, 5.seconds, 5.seconds.plus(200.millis))
  implicit private val system: ActorSystem = ActorSystem("raft-kv-akka")
  implicit private val materializer: ActorMaterializer = ActorMaterializer()

  private def serveStatics(websocketSource: Source[Message, Any]): Future[Http.ServerBinding] = {
    import akka.http.scaladsl.server.Directives._

    val staticResources =
      get {
        path("ws") {
          extractUpgradeToWebSocket { upgrade =>
            complete(upgrade.handleMessagesWithSinkSource(Sink.foreach(println), websocketSource))
          }
        } ~ pathEndOrSingleSlash {
          getFromResource("static/index.html")
        } ~ pathPrefix("") {
          getFromResourceDirectory("static")
        }
      }

    Http().bindAndHandle(staticResources, "0.0.0.0", 8080)
  }

  ClusterSharding(system).start(
    typeName = RaftActor.Name,
    entityProps = RaftActor.props,
    settings = ClusterShardingSettings(system),
    extractShardId = RaftActor.extractShardId,
    extractEntityId = RaftActor.extractEntityId
  )

  val RaftRegionRef: ActorRef = ClusterSharding(system).shardRegion(RaftActor.Name)

  def main(args: Array[String]): Unit = {
    val clusterSettings = Cluster.get(system).settings

    if (args.isEmpty) {
      val ids: Vector[Id] = (0 until clusterSettings.MinNrOfMembers).map(idx => Id(idx))(collection.breakOut)

      val websocketSource: Source[Message, ActorRef] =
        Source.actorRef[Message](1024, OverflowStrategy.dropHead)
          .mapMaterializedValue { ref =>
            ids.foreach(RaftRegionRef ! StatusRef(_, ref))
            ref
          }

      val httpServer = serveStatics(websocketSource)

      ids.foreach { id =>
        RaftRegionRef ! NodesInitialized(id, ids)
      }

      Source.tick(Duration.Zero, 200.millis, ids).runForeach(_.foreach(id => RaftRegionRef ! SendReport(id)))

      val state = mutable.Map.empty[String, String]

      Source.tick(20.seconds, 15.seconds, ids).runForeach { ids =>
        val added = ListBuffer.empty[(String, String)]
        val removed = ListBuffer.empty[String]
        val sets = (0 to Random.nextInt(5)).map { i =>
          val rand = BigInt.probablePrime(100, Random).toString(16)
          val key = "k" + rand
          val value = "v" + rand
          added += key -> value
          SetValue(_: Id, key, value)
        }
        val removes = (0 to Random.nextInt(5)).flatMap { _ =>
          if (state.nonEmpty) {
            val idx = Random.nextInt(state.size)
            val (key, _) = state.iterator.drop(idx).next()
            removed += key
            Some(RemoveValue(_: Id, key))
          } else None
        }
        for {
          set <- sets
          id <- ids
        } {
          RaftRegionRef ! set(id)
        }
        for {
          remove <- removes
          id <- ids
        } {
          RaftRegionRef ! remove(id)
        }
        state ++= added
        state --= removed
      }
    }
  }


}
