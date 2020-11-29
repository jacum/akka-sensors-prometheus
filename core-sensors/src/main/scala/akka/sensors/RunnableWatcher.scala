package akka.sensors

import java.lang.management.{ManagementFactory, ThreadMXBean}
import java.util.concurrent.Executors

import akka.sensors.RunnableWatcher.stackTraceToString
import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap
import scala.concurrent.BlockContext.withBlockContext
import scala.concurrent.duration._
import scala.concurrent.{BlockContext, CanAwait}
import scala.util.control.NonFatal

trait RunnableWatcher {
  def apply[T](f: => T): T
  def start(): () => Unit
}

object RunnableWatcher extends LazyLogging {

  type ThreadId = java.lang.Long

  type StartTime = java.lang.Long

  private lazy val threads = ManagementFactory.getThreadMXBean

  def apply(
    tooLongRunThreshold: Duration,
    checkInterval: Duration = 1.second,
    maxDepth: Int = 300,
    threads: ThreadMXBean = threads
  ): RunnableWatcher = {

    val cache = TrieMap.empty[ThreadId, StartTime]

    val runnable = new Runnable with LazyLogging {
      def run(): Unit =
        try {
          val currentTime = System.nanoTime()
          for {
            (threadId, startTime) <- cache
            duration = (currentTime - startTime).nanos
            if duration >= tooLongRunThreshold
            _          <- cache.remove(threadId)
            threadInfo <- Option(threads.getThreadInfo(threadId, maxDepth))
          } {
            val threadName          = threadInfo.getThreadName
            val stackTrace          = threadInfo.getStackTrace
            val formattedStackTrace = stackTraceToString(stackTrace)
            logger.error(s"Detected a thread that is locked for ${duration.toMillis} ms: $threadName, current state:\t$formattedStackTrace")
          }
        } catch {
          case NonFatal(failure) => logger.error(s"failed to check hanging threads: $failure", failure)
        }
    }

    AkkaSensors.executor.scheduleWithFixedDelay(runnable, checkInterval.length, checkInterval.length, checkInterval.unit)

    val add = (threadId: ThreadId) => {
      val startTime = System.nanoTime()
      cache.put(threadId, startTime)
      ()
    }

    val remove = (threadId: ThreadId) => {
      cache.remove(threadId)
      ()
    }

    apply(add, remove)
  }

  def apply(
    add: RunnableWatcher.ThreadId => Unit,
    remove: RunnableWatcher.ThreadId => Unit
  ): RunnableWatcher =
    new RunnableWatcher {

      def apply[T](f: => T): T = {
        val stop = start()
        try f
        finally stop()
      }

      def start(): () => Unit = {
        val threadId = Thread.currentThread().getId
        add(threadId)
        () => remove(threadId)
      }
    }

  def stackTraceToString(xs: Array[StackTraceElement]): String = xs.mkString("\tat ", "\n\tat ", "")

}

object BlockingWatcher extends LazyLogging {

  trait Surround { def apply[T](f: () => T): T }

  val Empty: Surround = new Surround {
    override def apply[T](f: () => T): T = f()
  }

  def apply(enabled: Boolean): Surround =
    if (enabled)
      new Surround {
        override def apply[T](f: () => T): T = {
          val nonTracking = BlockContext.current
          val tracking = new BlockContext {
            override def blockOn[A](thunk: => A)(implicit permission: CanAwait): A = {
              val thread     = Thread.currentThread()
              val stacktrace = stackTraceToString(thread.getStackTrace)
              val name       = thread.getName
              logger.warn(s"Thread $name is about to block on: $stacktrace")
              nonTracking.blockOn(thunk)
            }
          }
          withBlockContext(tracking)(f())
        }
      }
    else Empty
}
