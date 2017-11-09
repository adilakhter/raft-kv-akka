package pl.edu.agh.iosr.raft.command

import pl.edu.agh.iosr.raft.RaftActor
import pl.edu.agh.iosr.raft.model.Id
import play.api.libs.json._

import scala.collection.mutable

sealed trait Command {
  def apply(state: mutable.Map[String, String]): Unit
}

final case class SetValue(target: Id, key: String, value: String) extends Command with RaftActor.ClusterShardedMessage {
  override def apply(state: mutable.Map[String, String]): Unit = state += key -> value
}

object SetValue {
  implicit val setValueFormat: Writes[SetValue] = Json.writes[SetValue]
}

case object Init extends Command {
  override def apply(state: mutable.Map[String, String]): Unit = ()
}

object Command {
  implicit val format: Writes[Command] = {
    case msg: SetValue => SetValue.setValueFormat.writes(msg)
    case Init => JsObject(Map("name" -> JsString("Init")))
  }
}