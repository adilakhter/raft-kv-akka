package pl.edu.agh.iosr.raft

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import pl.edu.agh.iosr.raft.RaftActor.{NodesInitialized, SendReport, StatusRef}
import pl.edu.agh.iosr.raft.command.SetValue
import pl.edu.agh.iosr.raft.model.{Id, Term}
import pl.edu.agh.iosr.raft.status._
import play.api.libs.json._

import scala.concurrent.Future

object Simulation extends App {

  import scala.concurrent.duration._

  val Nodes = 5
  val Config = RaftConfig(2.second, 5.seconds, 5.seconds.plus(200.millis))

  implicit private val system: ActorSystem = ActorSystem("raft-kv-akka")
  implicit private val materializer: ActorMaterializer = ActorMaterializer()
  implicit private val termFormat: Writes[Term] = term => JsNumber(BigDecimal(term.value))
  implicit private val idFormat: Writes[Id] = id => JsString(id.value.toString)
  implicit private val stateFormat: Writes[ActorState] = state => JsString(state.productPrefix)
  implicit private val reportFormat: Writes[ActorStateReport] = Json.writes[ActorStateReport]

  val refs: Vector[ActorRef] = (0 until Nodes).map(idx => system.actorOf(Props(new RaftActor(Id(idx), Config))))(collection.breakOut)

  val websocketSource: Source[Message, ActorRef] =
    Source.actorRef[ActorStateReport](1024, OverflowStrategy.dropHead)
      .map(rep => TextMessage(Json.toJson(rep).toString()))
      .mapMaterializedValue { ref =>
        refs.foreach(_ ! StatusRef(ref))
        ref
      }

  def serveStatics(): Future[Http.ServerBinding] = {
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

  val httpServer = serveStatics()

  Source.tick(Duration.Zero, 100.millis, SendReport).runForeach(cmd => refs.foreach(_ ! cmd))

  refs.foreach(_ ! NodesInitialized(refs))

  Thread.sleep(20.seconds.toMillis)

  refs.foreach(_ ! SetValue("lol", "abc"))

  Thread.sleep(20.seconds.toMillis)

  refs.foreach(_ ! SetValue("lol2", "abc"))
  refs.foreach(_ ! SetValue("lol3", "abc"))
}
