package kentuckymule.core

import java.util

import dotty.tools.dotc.core.Contexts.Context
import CompletersQueue._

import scala.collection.JavaConverters._

class CompletersQueue(queueStrategy: QueueStrategy = RolloverQeueueStrategy) {

  def completers: Seq[Completer] = completionJobs.iterator().asScala.map(_.completer).toSeq

  private val completionJobs: util.Deque[CompletionJob] = new util.ArrayDeque[CompletionJob]()

  def queueCompleter(completer: Completer, pushToTheEnd: Boolean = true): Unit = {
    val completionJob = new CompletionJob(completer)
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
            case CompleteResult(spawnedJobs) =>
              var i = 0
              while (i < spawnedJobs.size) {
                completionJobs.addLast(spawnedJobs.get(i))
                i = i + 1
              }
            case IncompleteResult(blockingJob) =>
              missedDeps += 1
              queueIncompleteDependencyJobs(attemptedCompletionJob = completionJob, blockingJob = blockingJob)
          }
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

  private def queueIncompleteDependencyJobs(attemptedCompletionJob: CompletionJob,
                                            blockingJob: CompletionJob): Unit = {
    queueStrategy match {
      case RolloverQeueueStrategy =>
        completionJobs.add(blockingJob)
        completionJobs.add(attemptedCompletionJob)
      case CollectingPendingJobsQueueStrategy => sys.error("unsupported queue strategy")
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

  sealed class QueueStrategy
  case object RolloverQeueueStrategy extends QueueStrategy
  case object CollectingPendingJobsQueueStrategy extends QueueStrategy
}
