package pl.edu.agh.iosr.raft

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import pl.edu.agh.iosr.raft.RaftActor.{NodesInitialized, SendReport, StatusRef}
import pl.edu.agh.iosr.raft.command.SetValue
import pl.edu.agh.iosr.raft.model.Id

import scala.concurrent.Future

object Simulation {

  import scala.concurrent.duration._

  private val Nodes = 5
  private implicit val Config: RaftConfig = RaftConfig(2.second, 5.seconds, 5.seconds.plus(200.millis))

  implicit private val system: ActorSystem = ActorSystem("raft-kv-akka")
  implicit private val materializer: ActorMaterializer = ActorMaterializer()

  //todo
  private def isSupervisor = true

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

    if (isSupervisor) {
      val ids: Vector[Id] = (0 until Nodes).map(idx => Id(idx))(collection.breakOut)

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

      Source.tick(Duration.Zero, 100.millis, ids).runForeach(_.foreach(id => RaftRegionRef ! SendReport(id)))

      Thread.sleep(20.seconds.toMillis)

      ids.foreach(RaftRegionRef ! SetValue(_, "lol", "abc"))

      Thread.sleep(20.seconds.toMillis)

      ids.foreach(RaftRegionRef ! SetValue(_, "lol2", "abc"))
      ids.foreach(RaftRegionRef ! SetValue(_, "lol3", "abc"))
    }
  }


}
