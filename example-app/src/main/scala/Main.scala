package nl.pragmasoft.akka.sensors

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.ing.app.recipe.javadsl.Interaction
import com.ing.app.runtime.akka.internal.InteractionManagerLocal
import com.ing.app.runtime.akka.{Akkaapp, AkkaappConfig}
import com.ing.app.runtime.scaladsl.InteractionInstance
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.cassandraunit.utils.EmbeddedCassandraServerHelper.startEmbeddedCassandra
import org.http4s.server.Server
import org.springframework.context.annotation.AnnotationConfigApplicationContext

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

object Main extends IOApp with LazyLogging {
  val cassandra = startEmbeddedCassandra("cassandra-server.yaml")

  override def run(args: List[String]): IO[ExitCode] = {
    val config = ConfigFactory.load()
    val appy = config.getConfig("app")

    implicit val system: ActorSystem = ActorSystem("test", config)
    implicit val executionContext: ExecutionContext = system.dispatcher
    system.actorOf(Props[ClusterEventWatch], name = "ClusterListener")

    val metricsPort = appy.getInt("metrics-port")
    val loggingEnabled = appy.getBoolean("api-logging-enabled")
    logger.info(s"Logging of API: $loggingEnabled  - MUST NEVER BE SET TO 'true' IN PRODUCTION")

    val configurationClasses = appy.getStringList("interaction-configuration-classes")

    val interactions = {
      if (configurationClasses.size() == 0) {
        logger.warn("No interactions configured, probably interaction-configuration-classes config parameter is empty")
      }
      (configurationClasses.asScala map { configurationClass =>
        val configClass = Class.forName(configurationClass)
        val ctx = new AnnotationConfigApplicationContext()
        ctx.register(configClass)
        ctx.refresh()
        val interactionsAsJavaMap: java.util.Map[String, Interaction] =
          ctx.getBeansOfType(classOf[com.ing.app.recipe.javadsl.Interaction])
        val interactions = interactionsAsJavaMap.asScala.values.map(InteractionInstance.unsafeFrom).toList
        logger.info(s"Loaded ${interactions.size} interactions from $configurationClass: ${interactions.sortBy(_.name).map(_.name).mkString(",")}")
        interactions
      } toList).flatten
    }

    val interactionManager = new InteractionManagerLocal(interactions)

    val appConfig = AkkaappConfig(
      interactionManager = interactionManager,
      appActorProvider = AkkaappConfig.appProviderFrom(config),
      timeouts = AkkaappConfig.Timeouts.from(config),
      appValidationSettings = AkkaappConfig.appValidationSettings.from(config)
    )(system)

    logger.info("Starting Akka app...")
    val app = Akkaapp.withConfig(appConfig)

    val mainResource: Resource[IO, Server[IO]] =
      for {
        _ <- Resource.liftF(IO.async[Unit] { callback =>
          Cluster(system).registerOnMemberUp {
            logger.info("Akka cluster is now up")
            callback(Right(()))
          }
        })
        metricsService <- MetricService.resource(
          InetSocketAddress.createUnresolved("0.0.0.0", metricsPort)
        )
      } yield metricsService

    mainResource.use(metricService => {
      logger.info(s"appy started at ${metricService.address}/${metricService.baseUri}, enabling the readiness in Akka management")
      appReadinessCheck.enable()
      IO.never
    }
    ).as(ExitCode.Success)
  }
}
