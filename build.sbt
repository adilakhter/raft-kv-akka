import com.typesafe.sbt.SbtMultiJvm.multiJvmSettings
import sbt.protocol.testing.TestResult.{Failed, Passed}

val Versions = new {
  val Akka = "2.5.11"
  val AkkaHttp = "10.1.1"
  val PlayJson = "2.6.9"
  val Logback = "1.2.3"
  val ScalaTest = "3.0.5"
}

name := "raft-kv-akka"
organization := "pl.edu.agh"
version := "0.1-SNAPSHOT"
scalaVersion := "2.12.5"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-slf4j" % Versions.Akka,
  "com.typesafe.akka" %% "akka-http" % Versions.AkkaHttp,
  "com.typesafe.akka" %% "akka-cluster" % Versions.Akka,
  "com.typesafe.akka" %% "akka-cluster-sharding" % Versions.Akka,
  "com.typesafe.play" %% "play-json" % Versions.PlayJson,
  "ch.qos.logback" % "logback-classic" % Versions.Logback,
  "com.typesafe.akka" %% "akka-multi-node-testkit" % Versions.Akka % Test,
  "org.scalatest" %% "scalatest" % Versions.ScalaTest % Test,
)

//enable multi-jvm testing
multiJvmSettings
configs(MultiJvm)

// disable parallel tests
parallelExecution in Test := false

// make sure that MultiJvm tests are executed by the default test target,
// and combine the results from ordinary test and multi-jvm tests
executeTests in Test := {
  val testResults = (executeTests in Test).value
  val multiNodeResults = (executeTests in MultiJvm).value
  val overall =
    if (Seq(testResults.overall, multiNodeResults.overall).forall(_ == Passed)) Passed
    else Failed
  Tests.Output(overall,
    testResults.events ++ multiNodeResults.events,
    testResults.summaries ++ multiNodeResults.summaries)
}

mainClass in assembly := Some("pl.edu.agh.iosr.raft.Simulation")
assemblyJarName in assembly := "raft.jar"
test in assembly := {}