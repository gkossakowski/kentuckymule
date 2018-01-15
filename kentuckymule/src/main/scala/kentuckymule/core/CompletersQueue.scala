package kentuckymule.core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import CompletersQueue._

import scala.collection.JavaConverters._

class CompletersQueue(queueStrategy: QueueStrategy = CollectingPendingJobsQueueStrategy) {

  def completers: Seq[Completer] = completionJobs.iterator().asScala.map(x => x.asInstanceOf[CompletionJob].completer).toSeq

  private val completionJobs: util.Deque[QueueJob] = new util.ArrayDeque[QueueJob]()

  def queueCompleter(completer: Completer, pushToTheEnd: Boolean = true): Unit = {
    val completionJob = CompletionJob.createOrFetch(completer)
    if (pushToTheEnd)
      completionJobs.add(completionJob)
    else
      completionJobs.addFirst(completionJob)
  }

  def processJobQueue(memberListOnly: Boolean,
                      listener: JobQueueProgressListener = NopJobQueueProgressListener)(implicit ctx: Context):
  CompleterStats = {
    var steps = 0
    var missedDeps = 0
    try {
      while (!completionJobs.isEmpty) {
        steps += 1
        if (ctx.verbose)
          println(s"Step $steps/${steps + completionJobs.size - 1}")
        val completionJob = completionJobs.remove()
        if (ctx.verbose)
          println(s"Trying to complete $completionJob")
        if (!completionJob.isCompleted) {
          val res = completionJob.complete(memberListOnly)
          if (ctx.verbose)
            println(s"res = $res")
          res match {
            case cr: CompleteResult =>
              completeJob(completionJob, cr)
            case IncompleteResult(blockingJob) =>
              missedDeps += 1
              queueIncompleteDependencyJobs(attemptedJob = completionJob, blockingJob = blockingJob)
          }
        } else {
          postComplete(completionJob)
        }
        listener.thick(completionJobs.size, steps)
      }
    } catch {
      case ex: Exception =>
        println(s"steps = $steps, missedDeps = $missedDeps")
        throw ex
    }
    listener.allComplete()
    CompleterStats(steps, missedDeps)
  }

  private def queueIncompleteDependencyJobs(attemptedJob: QueueJob,
                                            blockingJob: QueueJob): Unit = {
    queueStrategy match {
      case RolloverQeueueStrategy =>
        completionJobs.add(blockingJob)
        completionJobs.add(attemptedJob)
      case CollectingPendingJobsQueueStrategy =>
        if (blockingJob.queueStore.pendingJobs == null)
          blockingJob.queueStore.pendingJobs = new util.ArrayList[QueueJob]()
        blockingJob.queueStore.pendingJobs.add(attemptedJob)
        completionJobs.add(blockingJob)
    }
  }

  private def completeJob(attemptedCompletionJob: QueueJob, completeResult: CompleteResult): Unit = {
    appendAllJobs(completeResult.spawnedJobs)
    postComplete(attemptedCompletionJob)
  }

  private def postComplete(completionJob: QueueJob): Unit = {
    queueStrategy match {
      case RolloverQeueueStrategy => ()
      case CollectingPendingJobsQueueStrategy =>
        val pendingJobs = completionJob.queueStore.pendingJobs
        if (pendingJobs != null) {
          appendAllJobs(pendingJobs)
          pendingJobs.clear()
        }
    }
  }

  private def appendAllJobs(xs: util.ArrayList[QueueJob]): Unit = {
    var i = 0
    while (i < xs.size) {
      val job = xs.get(i)
      completionJobs.addLast(job)
      i = i + 1
    }
  }

}

object CompletersQueue {
  case class CompleterStats(processedJobs: Int, dependencyMisses: Int)

  trait JobQueueProgressListener {
    def thick(queueSize: Int, completed: Int): Unit
    def allComplete(): Unit
  }
  object NopJobQueueProgressListener extends JobQueueProgressListener {
    override def thick(queueSize: Int, completed: Int): Unit = ()
    override def allComplete(): Unit = ()
  }

  sealed trait QueueStrategy
  case object RolloverQeueueStrategy extends QueueStrategy
  case object CollectingPendingJobsQueueStrategy extends QueueStrategy
}
