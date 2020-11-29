package akka.dispatch

import DispatcherInstrumentationWrapper.Run

import scala.PartialFunction.condOpt

object ScalaRunnableWrapper {
  def unapply(runnable: Runnable): Option[Run => Runnable] = condOpt(runnable) {
    case runnable: Batchable => new OverrideBatchable(runnable, _)
  }

  class OverrideBatchable(self: Runnable, r: Run) extends Batchable with Runnable {
    def run(): Unit = r(() => self.run())
  }
}