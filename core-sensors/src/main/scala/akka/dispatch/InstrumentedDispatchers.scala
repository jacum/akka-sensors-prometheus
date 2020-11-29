package akka.dispatch

import java.util.concurrent._
import java.util.concurrent.atomic.LongAdder
import java.util.{ConcurrentModificationException, Collection => JCollection, List => JList}

import akka.dispatch.DispatcherInstrumentationWrapper.Run
import akka.event.Logging.{Error, Warning}
import akka.sensors.{MetricsBuilders, RunnableWatcher}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client.Histogram

import scala.PartialFunction.condOpt
import scala.collection.JavaConverters._
import scala.compat.Platform
import scala.concurrent.duration.{Duration, FiniteDuration}

object DispatcherMetrics extends MetricsBuilders {
  def namespace: String = "akka_sensors"
  def subsystem: String = "dispatchers"

  val queueTime: Histogram = millisHistogram
    .name("queue_time_millis")
    .help(s"Milliseconds in queue")
    .labelNames("dispatcher")
    .register(registry)
  val runTime: Histogram = millisHistogram
    .name("run_time_millis")
    .help(s"Milliseconds running")
    .labelNames("dispatcher")
    .register(registry)
  val activeThreads: Histogram = valueHistogram(max = 32)
    .name("active_threads_total")
    .help(s"Active worker threads")
    .labelNames("dispatcher")
    .register(registry)
//
//  val totalThreads: Gauge = gauge
//    .name("threads_total")
//    .help("Total worker threads")
//    .labelNames("dispatcher")
//    .register(registry)

}

object AkkaRunnableWrapper {
  def unapply(runnable: Runnable): Option[Run => Runnable] =
    condOpt(runnable) {
      case runnable: Batchable => new BatchableWrapper(runnable, _)
      case runnable: Mailbox   => new MailboxWrapper(runnable, _)
    }

  class BatchableWrapper(self: Batchable, r: Run) extends Batchable {
    def run(): Unit          = r(() => self.run())
    def isBatchable: Boolean = self.isBatchable
  }

  class MailboxWrapper(self: Mailbox, r: Run) extends ForkJoinTask[Unit] with Runnable {
    def getRawResult: Unit          = self.getRawResult()
    def setRawResult(v: Unit): Unit = self.setRawResult(v)
    def exec(): Boolean             = r(() => self.exec())
    def run(): Unit = { exec(); () }
  }
}

class DispatcherInstrumentationWrapper(config: Config) {
  import DispatcherInstrumentationWrapper._
  import Helpers._

  private val instruments: List[InstrumentedRun] =
    List(
      if (config.getBoolean("instrumented-executor.measure-runs")) Some(meteredRun(config.getString("id"))) else None,
      if (config.getBoolean("instrumented-executor.watch-runs")) Some(watchedRun(config.getString("id"),
        config.getMillisDuration("instrumented-executor.too-long-run"),
        config.getMillisDuration("instrumented-executor.check-interval"))) else None,
    ) flatten

  def apply(runnable: Runnable, execute: Runnable => Unit): Unit = {
    val beforeRuns = for { f <- instruments } yield f()
    val run = new Run {
      def apply[T](run: () => T): T = {
        val afterRuns = for { f <- beforeRuns } yield f()
        try run()
        finally for { f <- afterRuns } f()
      }
    }
    execute(RunnableWrapper(runnable, run))
  }

  object RunnableWrapper {

    def apply(runnableParam: Runnable, r: Run): Runnable =
      runnableParam match {
        case AkkaRunnableWrapper(runnable)  => runnable(r)
        case ScalaRunnableWrapper(runnable) => runnable(r)
        case runnable                       => new Default(runnable, r)
      }

    class Default(self: Runnable, r: Run) extends Runnable {
      def run(): Unit = r(() => self.run())
    }
  }
}

object DispatcherInstrumentationWrapper {
  trait Run { def apply[T](f: () => T): T }

  type InstrumentedRun = () => BeforeRun
  type BeforeRun       = () => AfterRun
  type AfterRun        = () => Unit

  val Empty: InstrumentedRun = () => () => () => ()

  import DispatcherMetrics._
  def meteredRun(id: String): InstrumentedRun = {
    val currentWorkers = new LongAdder
    val queue          = queueTime.labels(id)
    val run            = runTime.labels(id)
    val active         = activeThreads.labels(id)

    () => {
      val created = Platform.currentTime
      () => {
        val started = Platform.currentTime
        queue.observe(started - created)
        currentWorkers.increment()
        active.observe(currentWorkers.intValue())
        () => {
          val stopped = Platform.currentTime
          run.observe(stopped - started)
          currentWorkers.decrement()
          active.observe(currentWorkers.intValue)
          ()
        }
      }
    }
  }

  def watchedRun(id: String, tooLongThreshold: Duration, checkInterval: Duration): InstrumentedRun = {
    val watcher = RunnableWatcher(tooLongRunThreshold = tooLongThreshold, checkInterval  = checkInterval)

    () => { () =>
      val stop = watcher.start()
      () => {
        stop()
        ()
      }
    }
  }
}

class InstrumentedExecutor(val config: Config, val prerequisites: DispatcherPrerequisites) extends ExecutorServiceConfigurator(config, prerequisites) {

  lazy val delegate: ExecutorServiceConfigurator =
    configurator(config.getString("instrumented-executor.delegate"))

  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = {
    val esf = delegate.createExecutorServiceFactory(id, threadFactory)
    new ExecutorServiceFactory {
      def createExecutorService: ExecutorService = esf.createExecutorService
    }
  }

  def configurator(executor: String): ExecutorServiceConfigurator =
    executor match {
      case null | "" | "fork-join-executor" ⇒ new ForkJoinExecutorConfigurator(config.getConfig("fork-join-executor"), prerequisites)
      case "thread-pool-executor"           ⇒ new ThreadPoolExecutorConfigurator(config.getConfig("thread-pool-executor"), prerequisites)
      case fqcn ⇒
        val args = List(classOf[Config] -> config, classOf[DispatcherPrerequisites] -> prerequisites)
        prerequisites.dynamicAccess
          .createInstanceFor[ExecutorServiceConfigurator](fqcn, args)
          .recover({
            case exception ⇒
              throw new IllegalArgumentException(
                """Cannot instantiate ExecutorServiceConfigurator ("executor = [%s]"), defined in [%s],
                make sure it has an accessible constructor with a [%s,%s] signature"""
                  .format(fqcn, config.getString("id"), classOf[Config], classOf[DispatcherPrerequisites]),
                exception
              )
          })
          .get
    }

}

trait InstrumentedDispatcher extends Dispatcher {

  private lazy val wrapper                       = new DispatcherInstrumentationWrapper(configurator.config)
  override def execute(runnable: Runnable): Unit = wrapper(runnable, super.execute)

  /**
   * Clone of [[Dispatcher.executorServiceFactoryProvider]]
   */
  protected[akka] override def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean =
    if (mbox.canBeScheduledForExecution(hasMessageHint, hasSystemMessageHint))
      if (mbox.setAsScheduled())
        try {
          wrapper(mbox, executorService.execute)
          true
        } catch {
          case _: RejectedExecutionException ⇒
            try {
              wrapper(mbox, executorService.execute)
              true
            } catch { //Retry once
              case e: RejectedExecutionException ⇒
                mbox.setAsIdle()
                eventStream.publish(Error(e, getClass.getName, getClass, "registerForExecution was rejected twice!"))
                throw e
            }
        }
      else false
    else false
}

/** Instrumented clone of [[akka.dispatch.DispatcherConfigurator]]. */
class InstrumentedDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends MessageDispatcherConfigurator(config, prerequisites) {

  import Helpers._

  private val instance = new Dispatcher(
    this,
    config.getString("id"),
    config.getInt("throughput"),
    config.getNanosDuration("throughput-deadline-time"),
    configureExecutor(),
    config.getMillisDuration("shutdown-timeout")
  ) with InstrumentedDispatcher

  def dispatcher(): MessageDispatcher = instance

}

/** Instrumented clone of [[akka.dispatch.PinnedDispatcherConfigurator]]. */
class InstrumentedPinnedDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends MessageDispatcherConfigurator(config, prerequisites) {
  import Helpers._

  private val threadPoolConfig: ThreadPoolConfig = configureExecutor() match {
    case e: ThreadPoolExecutorConfigurator ⇒ e.threadPoolConfig
    case _ ⇒
      prerequisites.eventStream.publish(
        Warning(
          "PinnedDispatcherConfigurator",
          this.getClass,
          "PinnedDispatcher [%s] not configured to use ThreadPoolExecutor, falling back to default config.".format(config.getString("id"))
        )
      )
      ThreadPoolConfig()
  }

  override def dispatcher(): MessageDispatcher =
    new PinnedDispatcher(this, null, config.getString("id"), config.getMillisDuration("shutdown-timeout"), threadPoolConfig) with InstrumentedDispatcher

}

object Helpers {

  /**
   * INTERNAL API
   */
  private[akka] implicit final class ConfigOps(val config: Config) extends AnyVal {
    def getMillisDuration(path: String): FiniteDuration = getDuration(path, TimeUnit.MILLISECONDS)

    def getNanosDuration(path: String): FiniteDuration = getDuration(path, TimeUnit.NANOSECONDS)

    private def getDuration(path: String, unit: TimeUnit): FiniteDuration =
      Duration(config.getDuration(path, unit), unit)
  }
}
