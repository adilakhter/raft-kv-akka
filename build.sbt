import com.typesafe.sbt.SbtMultiJvm.multiJvmSettings

val Versions = new {
  val Akka = "2.4.0"
  val Scalatest = "2.2.4"
}

name := "raft-kv-akka"
version := "0.1-SNAPSHOT"
scalaVersion := "2.11.7"

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
executeTests in Test := /*(executeTests in Test, executeTests in MultiJvm) map*/ {
  val testResults = (executeTests in Test).value
  val multiNodeResults = (executeTests in MultiJvm).value
  val overall =
    if (testResults.overall.id < multiNodeResults.overall.id)
      multiNodeResults.overall
    else
      testResults.overall
  Tests.Output(overall,
    testResults.events ++ multiNodeResults.events,
    testResults.summaries ++ multiNodeResults.summaries)
}