import com.typesafe.sbt.SbtMultiJvm.multiJvmSettings
import sbt.protocol.testing.TestResult.{Failed, Passed}

val Versions = new {
  val Akka = "2.5.6"
  val Scalatest = "3.0.4"
}

name := "raft-kv-akka"
organization := "pl.edu.agh"
version := "0.1-SNAPSHOT"
scalaVersion := "2.12.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-remote" % Versions.Akka,
  "com.typesafe.akka" %% "akka-multi-node-testkit" % Versions.Akka,
  "org.scalatest" %% "scalatest" % Versions.Scalatest
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
