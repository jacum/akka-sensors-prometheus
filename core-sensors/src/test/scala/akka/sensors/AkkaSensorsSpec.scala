package akka.sensors

import java.io.CharArrayWriter
import java.util.UUID

import akka.actor.{Actor, ActorSystem, NoSerializationVerificationNeeded, PoisonPill, Props}
import akka.pattern.ask
import akka.sensors.actor.InstrumentedActorMetrics
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.{Millis, Seconds, Span}

import scala.Console.println
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Random

class AkkaSensorsSpec extends AnyFreeSpec with LazyLogging with Eventually {

  import InstrumentedActors._
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(2, Seconds)), interval = scaled(Span(5, Millis)))

  implicit val ec: ExecutionContext = ExecutionContext.global
  private val system: ActorSystem = ActorSystem("instrumented")
  private val probeActor = system.actorOf(Props(classOf[InstrumentedProbe]), s"probe-${UUID.randomUUID().toString}")

  implicit val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  "Launch akka app, and ensure it works" - {

    "starts actor system and pings the bootstrap actor" in {
      pingActor
    }

    "ensure prometheus JMX scraping is working" in {

      for (_ <- 1 to 5) probeActor ! KnownError
      for (_ <- 1 to 100) probeActor ! UnknownMessage
      probeActor ! BlockTooLong
      for (_ <- 1 to 1000) {
        pingActor
      }

      probeActor ! PoisonPill

      Thread.sleep(100) // todo better condition?

      println(metrics)

      // todo assertions per feature
//      assert(content.split("\n").exists(_.startsWith("cassandra_cql")))
    }
  }

  def metrics(implicit registry: CollectorRegistry) = {
    val writer = new CharArrayWriter(16 * 1024)
    TextFormat.write004(writer, registry.metricFamilySamples)
    writer.toString
  }

  private def pingActor = {
    val r = Await.result(
      probeActor.ask(Ping)(Timeout.durationToTimeout(30 seconds)), 40 seconds)
    assert(r.toString == "Pong")
  }
}

object InstrumentedActors {

  case object Ping extends NoSerializationVerificationNeeded
  case object KnownError  extends NoSerializationVerificationNeeded
  case object UnknownMessage  extends NoSerializationVerificationNeeded
  case object BlockTooLong extends NoSerializationVerificationNeeded
  case object Pong extends NoSerializationVerificationNeeded

  class InstrumentedProbe extends Actor with InstrumentedActorMetrics {
    def receive: Receive = {
            case Ping =>
              Thread.sleep(Random.nextInt(3))
              sender() ! Pong
            case KnownError =>
              throw new Exception("known")
            case BlockTooLong =>
              Thread.sleep(6000)
    }
  }


}