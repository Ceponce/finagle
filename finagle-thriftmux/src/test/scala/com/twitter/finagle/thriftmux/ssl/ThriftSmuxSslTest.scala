package com.twitter.finagle.thriftmux.ssl

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{SslException, SslVerificationFailedException}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.thriftmux.ssl.ThriftSmuxSslTestComponents._
import com.twitter.finagle.thriftmux.thriftscala._
import com.twitter.util.{Await, Awaitable, Duration}
import org.scalatest.FunSuite
import org.scalatest.concurrent.Eventually

class ThriftSmuxSslTest extends FunSuite with Eventually {

  private[this] def assertGaugeIsZero(
    statsReceiver: InMemoryStatsReceiver,
    name: Array[String]
  ): Unit = statsReceiver.gauges.get(name) match {
    case Some(f) => assert(f() == 0.0)
    case None => // all good
  }

  private[this] def assertGaugeIsNonZero(
    value: Float
  )(statsReceiver: InMemoryStatsReceiver,
    name: Array[String]
  ): Unit = statsReceiver.gauges.get(name) match {
    case Some(f) => assert(f() == value)
    case None => fail()
  }

  def await[T](a: Awaitable[T], d: Duration = 2.seconds): T =
    Await.result(a, d)

  private[this] val assertGaugeIsOne = assertGaugeIsNonZero(1.0F) _
  private[this] val assertGaugeIsTwo = assertGaugeIsNonZero(2.0F) _

  private[this] def mkSuccessfulHelloRequest(client: TestService.MethodPerEndpoint): Unit = {
    val response = await(client.query("hello"))
    assert(response == "hellohello")
  }

  test("Single client and server results in server TLS connections incremented and decremented") {
    val serverStats = new InMemoryStatsReceiver
    val serverTlsConnections = Array("server", "tls", "connections")

    val server = mkTlsServer("server", serverStats)
    val client = mkTlsClient(getPort(server))

    assertGaugeIsZero(serverStats, serverTlsConnections)
    mkSuccessfulHelloRequest(client)
    assertGaugeIsOne(serverStats, serverTlsConnections)

    await(client.asClosable.close())
    eventually { assertGaugeIsZero(serverStats, serverTlsConnections) }
    await(server.close())
  }

  test("Single client and server results in client TLS connections incremented and decremented") {
    val clientStats = new InMemoryStatsReceiver
    val clientTlsConnections = Array("client", "tls", "connections")

    val server = mkTlsServer()
    val client = mkTlsClient(getPort(server), "client", clientStats)

    assertGaugeIsZero(clientStats, clientTlsConnections)
    mkSuccessfulHelloRequest(client)
    assertGaugeIsOne(clientStats, clientTlsConnections)

    await(client.asClosable.close())
    eventually { assertGaugeIsZero(clientStats, clientTlsConnections) }
    await(server.close())
  }

  test("Multiple clients and server results in server TLS connections incremented and decremented") {
    val serverStats = new InMemoryStatsReceiver

    val server = mkTlsServer("server", serverStats)
    val serverPort = getPort(server)
    val client1 = mkTlsClient(serverPort, "client1")
    val client2 = mkTlsClient(serverPort, "client2")

    val serverTlsConnections = Array("server", "tls", "connections")

    assertGaugeIsZero(serverStats, serverTlsConnections)
    mkSuccessfulHelloRequest(client1)
    assertGaugeIsOne(serverStats, serverTlsConnections)

    mkSuccessfulHelloRequest(client2)
    assertGaugeIsTwo(serverStats, serverTlsConnections)

    await(client1.asClosable.close())
    eventually { assertGaugeIsOne(serverStats, serverTlsConnections) }

    await(client2.asClosable.close())
    await(server.close())
    eventually { assertGaugeIsZero(serverStats, serverTlsConnections) }
  }

  test(
    "Multiple clients and server results in separate client TLS connections incremented and decremented"
  ) {
    val client1Stats = new InMemoryStatsReceiver
    val client2Stats = new InMemoryStatsReceiver

    val server = mkTlsServer("server")
    val serverPort = getPort(server)
    val client1 = mkTlsClient(serverPort, "client1", client1Stats)
    val client2 = mkTlsClient(serverPort, "client2", client2Stats)

    val client1TlsConnections = Array("client1", "tls", "connections")
    val client2TlsConnections = Array("client2", "tls", "connections")

    assertGaugeIsZero(client1Stats, client1TlsConnections)
    assertGaugeIsZero(client2Stats, client2TlsConnections)

    mkSuccessfulHelloRequest(client1)
    assertGaugeIsOne(client1Stats, client1TlsConnections)
    assertGaugeIsZero(client2Stats, client2TlsConnections)

    mkSuccessfulHelloRequest(client2)
    assertGaugeIsOne(client1Stats, client1TlsConnections)
    assertGaugeIsOne(client2Stats, client2TlsConnections)

    await(client1.asClosable.close())
    eventually {
      assertGaugeIsZero(client1Stats, client1TlsConnections)
      assertGaugeIsOne(client2Stats, client2TlsConnections)
    }

    await(client2.asClosable.close())
    await(server.close())
    eventually {
      assertGaugeIsZero(client1Stats, client1TlsConnections)
      assertGaugeIsZero(client2Stats, client2TlsConnections)
    }
  }

  test("Single client and rejecting server results in both sides TLS connections at 0") {
    val serverStats = new InMemoryStatsReceiver
    val serverTlsConnections = Array("server", "tls", "connections")

    val clientStats = new InMemoryStatsReceiver
    val clientTlsConnections = Array("client", "tls", "connections")

    val server = mkTlsServer("server", serverStats, NeverValidServerSide)
    val client = mkTlsClient(getPort(server), "client", clientStats)

    assertGaugeIsZero(serverStats, serverTlsConnections)
    assertGaugeIsZero(clientStats, clientTlsConnections)

    intercept[SslException] {
      await(client.query("hello"))
    }

    eventually { assertGaugeIsZero(serverStats, serverTlsConnections) }
    eventually { assertGaugeIsZero(clientStats, clientTlsConnections) }

    await(client.asClosable.close())
    await(server.close())

    eventually {
      assertGaugeIsZero(serverStats, serverTlsConnections)
      assertGaugeIsZero(clientStats, clientTlsConnections)
    }
  }

  test("Single rejecting client and server results in both sides TLS connections at 0") {
    val serverStats = new InMemoryStatsReceiver
    val serverTlsConnections = Array("server", "tls", "connections")

    val clientStats = new InMemoryStatsReceiver
    val clientTlsConnections = Array("client", "tls", "connections")

    val server = mkTlsServer("server", serverStats)
    val client = mkTlsClient(getPort(server), "client", clientStats, NeverValidClientSide)

    assertGaugeIsZero(serverStats, serverTlsConnections)
    assertGaugeIsZero(clientStats, clientTlsConnections)

    intercept[SslVerificationFailedException] {
      await(client.query("hello"))
    }

    eventually {
      assertGaugeIsZero(serverStats, serverTlsConnections)
      assertGaugeIsZero(clientStats, clientTlsConnections)
    }

    await(client.asClosable.close())
    await(server.close())

    eventually {
      assertGaugeIsZero(serverStats, serverTlsConnections)
      assertGaugeIsZero(clientStats, clientTlsConnections)
    }
  }
}
