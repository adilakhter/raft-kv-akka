package com.packt.akka

import akka.remote.testkit.MultiNodeSpecCallbacks
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}

trait BasicMultiNodeSpec extends MultiNodeSpecCallbacks
                          with FlatSpecLike
                          with BeforeAndAfterAll {
  override def beforeAll = multiNodeSpecBeforeAll()

  override def afterAll = multiNodeSpecAfterAll()
}