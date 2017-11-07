package pl.edu.agh.iosr.raft.command

import scala.collection.mutable

sealed trait Command {
  def apply(state: mutable.Map[String, String]): Unit
}

final case class SetValue(key: String, value: String) extends Command {
  override def apply(state: mutable.Map[String, String]): Unit = state += key -> value
}

case object Init extends Command {
  override def apply(state: mutable.Map[String, String]): Unit = ()
}