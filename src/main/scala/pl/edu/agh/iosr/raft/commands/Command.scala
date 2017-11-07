package pl.edu.agh.iosr.raft.commands

sealed trait Command

final case class SetValue(key: String, value: String) extends Command

case object Init extends Command