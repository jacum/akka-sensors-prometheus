package akka.actor

import akka.sensors.AkkaSensorsExtension

import scala.util.control.NonFatal

trait InstrumentedActorMetrics extends Actor {
  self: Actor =>
  import akka.sensors.MetricOps._

  def actorTag: String = this.getClass.getSimpleName

  private val metrics           = AkkaSensorsExtension(this.context.system)
  private val receiveTime       = metrics.receiveTime.labels(actorTag)
  private val exceptions        = metrics.exceptions.labels(actorTag)
  private val activeActors      = metrics.activeActors.labels(actorTag)
  private val unhandledMessages = metrics.unhandledMessages.labels(actorTag)

  private val activityTimer = metrics.activityTime.labels(actorTag).startTimer()

  protected[akka] override def aroundReceive(receive: Receive, msg: Any): Unit =
    try receiveTime.observeExecution(super.aroundReceive(receive, msg))
    catch {
      case NonFatal(e) =>
        exceptions.inc()
        throw e
    }

  protected[akka] override def aroundPreStart(): Unit = {
    super.aroundPreStart()
    activeActors.inc()
  }

  protected[akka] override def aroundPostStop(): Unit = {
    activeActors.dec()
    activityTimer.observeDuration()
    super.aroundPostStop()
  }

  override def unhandled(message: Any): Unit = {
    unhandledMessages.inc()
    super.unhandled(message)
  }

}

//trait MeteredStatefulPersistentStreamActor[State, UpstreamMessage, PersistEvent, DownstreamMessage] extends PersistentActor with ActorLogging {
//
//  implicit val ec: ExecutionContext = context.dispatcher
//  private val actorLifetime = new SimpleTimer()
//  private var eventsCounter = 0
//
//  protected val metrics: ActorMetrics
//
//  override def preStart(): Unit = metrics.numberOfActors.inc()
//
//  override def postStop(): Unit = {
//    metrics.activityTimeSeconds.observe(actorLifetime.elapsedSeconds)
//    metrics.numberOfActors.dec()
//  }
//
//
//  override def receiveCommand: Receive = {
//
//    case CommittableKafkaMessage(value, source, offset) if Try(value.asInstanceOf[UpstreamMessage]).isSuccess =>
//      val receiveTimer = new SimpleTimer()
//      val streamActor  = sender()
//
//      if (eventsCounter >= maximumAllowedEvents) {
//        log.error(
//          s"Journal events of persistent actor $persistenceId exceed allowed maximum of $maximumAllowedEvents, dropping incoming command. " +
//            s"This may be caused by inconsistent input or some other error. This is a serious problem that needs to be investigated asap."
//        )
//        streamActor ! StreamElementOutWithAck(CommittableKafkaMessage(Left(JournalOverflow(persistenceId, maximumAllowedEvents)), source, offset))
//
//      } else {
//
//        mapToEventAndDownstream(stateRef.value, value.asInstanceOf[UpstreamMessage]) match {
//
//          case Left(error) =>
//            streamActor ! StreamElementOutWithAck(CommittableKafkaMessage(Left(error), source, offset))
//
//          case Right(result) =>
//            def sendDownstream(): Unit =
//              streamActor ! StreamElementOutWithAck(CommittableKafkaMessage(Right(result.downstream), source, offset))
//
//            result.event match {
//              case Some(e) =>
//                val persistTimer = new SimpleTimer()
//                persist(e) { e =>
//                  eventsCounter = eventsCounter + 1
//                  metrics.persistTimeSeconds.observe(persistTimer.elapsedSeconds())
//                  updateState(e)
//                  sendDownstream()
//                }
//              case None =>
//                sendDownstream()
//            }
//        }
//      }
//      metrics.receiveTimeSeconds.observe(receiveTimer.elapsedSeconds())
//
//  }
//
//  override def receiveRecover: Receive = {
//    case RecoveryCompleted =>
//      metrics.recoveryEvents.observe(eventsCounter.toDouble)
//      metrics.recoveryTimeSeconds.observe(actorLifetime.elapsedSeconds)
//    case e =>
//      eventsCounter = eventsCounter + 1
//      Try(updateState(e.asInstanceOf[PersistEvent])).toEither.left.map { ex =>
//        log.warning(s"Actor ${actorKey.value} failed to recover event ${e.getClass.getSimpleName}: ${ex.getMessage}")
//      }
//  }
//
//  protected def initialState: State
//
//  protected override def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit =
//    log.error(cause, s"${actorKey.value} persist rejected seq $seqNr")
//
//  protected override def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit =
//    log.error(cause, s"${actorKey.value} persist failure seq $seqNr")
//
//  private def updateState(event: PersistEvent): Unit = stateRef.update(applyEventToState(_, event))
//}
