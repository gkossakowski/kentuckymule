package kentuckymule.queue

import java.util

import dotty.tools.dotc.core.Contexts.Context
import kentuckymule.core._
import kentuckymule.queue.JobQueue._
import kentuckymule.queue.QueueJob.{CompleteResult, IncompleteResult}

import scala.collection.JavaConverters._

class JobQueue(queueStrategy: QueueStrategy = CollectingPendingJobsQueueStrategy) {

  def completers: Seq[Completer] = completionJobs.iterator().asScala.map(x => x.asInstanceOf[CompletionJob].completer).toSeq

  private val completionJobs: util.Deque[QueueJob] = new util.ArrayDeque[QueueJob]()

  private var pendingJobsCount: Int = 0
  // jobs that were once pending and are either still pending or completed
  // I don't use Set here along with proper bookkeeping that would remove
  // completed jobs for performance reasons;
  // appending to an ArrayList is super quick and pretty much free
  private val possiblyPendingJobs: util.List[QueueJob] = new util.ArrayList[QueueJob]()

  def queueCompleter(completer: Completer, pushToTheEnd: Boolean = true): Unit = {
    val completionJob = CompletionJob.createOrFetch(completer)
    queueJob(completionJob, pushToTheEnd)
  }

  def queueJob(queueJob: QueueJob, pushToTheEnd: Boolean = true): Unit = {
    if (pushToTheEnd)
      completionJobs.add(queueJob)
    else
      completionJobs.addFirst(queueJob)
    queueJob.queueStore.queued = true
  }

  def processJobQueue(memberListOnly: Boolean,
                      listener: JobQueueProgressListener = NopJobQueueProgressListener)(implicit ctx: Context):
  JobQueueResult = {
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
    if (pendingJobsCount > 0) {
      val cycle = jobsFindCycle(possiblyPendingJobs)
      JobDependencyCycle(cycle)
    } else {
      listener.allComplete()
      CompleterStats(steps, missedDeps)
    }
  }

  private def queueIncompleteDependencyJobs(attemptedJob: QueueJob,
                                            blockingJob: QueueJob): Unit = {
    queueStrategy match {
      case RolloverQeueueStrategy =>
        queueJob(blockingJob)
        queueJob(attemptedJob)
      case CollectingPendingJobsQueueStrategy =>
        addPendingJob(queueJob = blockingJob, pendingJob = attemptedJob)
        // this conditional is crucial for queue cycle detection
        // we only add a job if it's not queued yet which means that it's
        // not been seen before and attemptedJob "discovered" it for the
        // first time. If we scheduled all jobs unconditionally, we'd keep
        // adding jobs in a cycle and keep queue indefinitely. By checking
        // this property we make sure that a job is in one of the three states:
        // - unqueued (the initial state)
        // - queued (sits in the main queue)
        // - pending (sits in the auxiliary queue of pending jobs of another job)
        // TODO: make these state transitions more explicit with a simple state machine
        if (!blockingJob.queueStore.queued)
          queueJob(blockingJob)
    }
  }

  private def addPendingJob(queueJob: QueueJob, pendingJob: QueueJob): Unit = {
    if (queueJob.queueStore.pendingJobs == null)
      queueJob.queueStore.pendingJobs = new util.ArrayList[QueueJob]()
    queueJob.queueStore.pendingJobs.add(pendingJob)
    pendingJobsCount += 1
    possiblyPendingJobs.add(pendingJob)
  }

  private def flushPendingJobs(queueJob: QueueJob): Unit = {
    val pendingJobs = queueJob.queueStore.pendingJobs
    if (pendingJobs != null) {
      appendAllJobs(pendingJobs)
      pendingJobsCount -= pendingJobs.size()
      pendingJobs.clear()
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
        flushPendingJobs(completionJob)
    }
  }

  private def appendAllJobs(xs: util.ArrayList[QueueJob]): Unit = {
    var i = 0
    while (i < xs.size) {
      val job = xs.get(i)
      queueJob(job)
      i = i + 1
    }
  }

}

object JobQueue {
  sealed trait JobQueueResult
  case class CompleterStats(processedJobs: Int, dependencyMisses: Int) extends JobQueueResult
  case class JobDependencyCycle(foundCycle: Seq[QueueJob]) extends JobQueueResult

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

  /**
    * Find dependency cycle amongst jobs passed as an argument. It reconstructs dependencies by
    * rerunning jobs and collecting IncompleteResult(blockingJob) into a hash map. This method
    * ignores already completed jobs.
    *
    * This method is very expensive and is intended to be used only for the final error reporting.
    * For that reason, it's implemented in a style that doesn't optimize for performance (except for
    * obvious O-style choices). In particular, it uses Scala collections that I found to be slower
    * than Java collections.
    *
    * The returned sequence is one of the found cycles. The order of elements in the sequence follows
    * the dependencies but they can be returned in arbitrary rotation.
    */
  //noinspection ReferenceMustBePrefixed
  private def jobsFindCycle(jobs: util.List[QueueJob])(implicit ctx: Context): Seq[QueueJob] = {
    import scala.collection.mutable.{Map, Set, Buffer}
    val deps: Map[QueueJob, QueueJob] = Map.empty[QueueJob, QueueJob]
    val pendingJobs: Set[QueueJob] = Set.empty[QueueJob]
    jobs.asScala foreach { job =>
      if (!job.isCompleted) {
        pendingJobs.add(job)
        // TODO: make memberListOnly a property of a queue and of a job
        val jobResult = job.complete(memberListOnly = false)
        jobResult match {
          case IncompleteResult(blockingJob) =>
            deps.put(job, blockingJob)
          case unexpectedCompleteResult: CompleteResult =>
            throw new IllegalArgumentException(
              s"""
                 |One of the submitted jobs has completed during `complete` operation.
                 |All jobs are expected to be either already complete or blocked on another job.
                 |job = $job
                 |unexpectedCompleteResult = $unexpectedCompleteResult
                 |""".stripMargin)
        }
      }
    }
    def walk(job: QueueJob): Seq[QueueJob] = {
      val visited: Set[QueueJob] = Set.empty
      val visitedBuf: Buffer[QueueJob] = Buffer.empty
      var current: QueueJob = job
      while (!visited.contains(current)) {
        visited.add(current)
        visitedBuf.append(current)
        current = deps.getOrElse(current,
          throw new IllegalArgumentException(s"Failed to find cycle for job = $job"))
      }
      visitedBuf
    }
    walk(pendingJobs.head)
  }
}
