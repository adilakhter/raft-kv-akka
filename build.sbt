import com.typesafe.sbt.SbtMultiJvm.multiJvmSettings
import sbt.protocol.testing.TestResult.{Failed, Passed}

val Versions = new {
  val Akka = "2.5.6"
  val AkkaHttp = "10.0.10"
  val PlayJson = "2.6.6"
  val ScalaTest = "3.0.4"
  val Logback = "1.2.3"
}

name := "raft-kv-akka"
organization := "pl.edu.agh"
version := "0.1-SNAPSHOT"
scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-remote" % Versions.Akka,
  "com.typesafe.akka" %% "akka-multi-node-testkit" % Versions.Akka,
  "com.typesafe.akka" %% "akka-slf4j" % Versions.Akka,
  "com.typesafe.akka" %% "akka-http" % Versions.AkkaHttp,
  "com.typesafe.play" %% "play-json" % Versions.PlayJson,
  "ch.qos.logback" % "logback-classic" % Versions.Logback,
  "org.scalatest" %% "scalatest" % Versions.ScalaTest
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
