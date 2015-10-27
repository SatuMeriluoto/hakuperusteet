package fi.vm.sade.hakuperusteet.db

import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}
import slick.util.AsyncExecutor
import scala.concurrent.ExecutionContext

object GlobalExecutionContext {
  val context = ExecutionContext.fromExecutor(new ThreadPoolExecutor(10, 10, 10, TimeUnit.SECONDS, new ArrayBlockingQueue[Runnable](100000)))
  val asyncExecutor: AsyncExecutor = AsyncExecutor("slick jdbc", 10, 100000)
}