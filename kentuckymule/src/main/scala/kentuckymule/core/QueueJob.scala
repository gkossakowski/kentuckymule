package kentuckymule.core
import java.util

import dotty.tools.dotc.core.Contexts

trait QueueJob {

  def complete(memberListOnly: Boolean)(implicit ctx: Contexts.Context): JobResult

  def isCompleted: Boolean

  val queueStore: QueueJobStore
}

/**
  * A stub for a store associated with a single queue job. In the future we can store here
  * a job continuation or a whole stack of jobs that are blocked on a given job.
  */
final class QueueJobStore {
  var pendingJobs: java.util.ArrayList[QueueJob] = _
}

sealed abstract class JobResult
case class CompleteResult(spawnedJobs: java.util.ArrayList[QueueJob]) extends JobResult
case class IncompleteResult(blockingJob: QueueJob) extends JobResult
