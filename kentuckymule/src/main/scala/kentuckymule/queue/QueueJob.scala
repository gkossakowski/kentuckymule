package kentuckymule.queue

import dotty.tools.dotc.core.Contexts
import kentuckymule.queue.QueueJob.JobResult

trait QueueJob {

  def complete(memberListOnly: Boolean)(implicit ctx: Contexts.Context): JobResult

  def isCompleted: Boolean

  val queueStore: QueueJobStore
}

object QueueJob {
  sealed abstract class JobResult
  case class CompleteResult(spawnedJobs: java.util.ArrayList[QueueJob]) extends JobResult
  case class IncompleteResult(blockingJob: QueueJob) extends JobResult
}
