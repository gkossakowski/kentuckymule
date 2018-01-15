package kentuckymule.queue

import java.util

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Contexts.ContextBase
import kentuckymule.queue.QueueJob.{CompleteResult, IncompleteResult}
import utest._

import scala.collection.JavaConverters._

object JobQueueTest extends TestSuite {
  def initCtx: Context = (new ContextBase).initialCtx
  val tests = this {
    implicit val ctx: Context = initCtx.fresh
    'basicTest {
      val jobQueue = new JobQueue()
      val testJob1 = new TestJob()
      val testJob2 = new TestJob()
      jobQueue.queueJob(testJob1)
      jobQueue.queueJob(testJob2)
      val stats = jobQueue.processJobQueue(memberListOnly = false)
      assert(testJob1.isCompleted)
      assert(testJob2.isCompleted)
      assert(stats.processedJobs == 2)
      assert(stats.dependencyMisses == 0)
    }
    'spawnedJobsAreProcessed {
      val jobQueue = new JobQueue()
      val spawnedJob1 = new TestJob()
      val spawnedJob2 = new TestJob()
      val testJob1 = new TestJob(spawnedJobs = spawnedJob1 :: Nil)
      val testJob2 = new TestJob(spawnedJobs = spawnedJob2 :: Nil)
      jobQueue.queueJob(testJob1)
      jobQueue.queueJob(testJob2)
      val stats = jobQueue.processJobQueue(memberListOnly = false)
      assert(testJob1.isCompleted)
      assert(testJob2.isCompleted)
      assume(spawnedJob1.isCompleted)
      assume(spawnedJob2.isCompleted)
      assert(stats.processedJobs == 4)
      assert(stats.dependencyMisses == 0)
    }
    'depsAreBlocking {
      val jobQueue = new JobQueue()
      val testJob1 = new TestJob()
      val testJob2 = new TestJob(deps = testJob1 :: Nil)
      jobQueue.queueJob(testJob2)
      jobQueue.queueJob(testJob1)
      val stats = jobQueue.processJobQueue(memberListOnly = false)
      assert(testJob1.isCompleted)
      assert(testJob2.isCompleted)
      // four jobs are processed because the sequence is:
      // 2 (blocked on 1) => add 1 (with 2 pending)
      // 1
      // 1 (already completed) => release pending (2)
      // 2
      assert(stats.processedJobs == 4)
      assert(stats.dependencyMisses == 1)
    }
    'detectCycle {
      val jobQueue = new JobQueue()
      val testJob1 = new TestJob()
      val testJob2 = new TestJob()
      val testJob3 = new TestJob()
      val testJob4 = new TestJob()
      testJob1.deps = testJob2 :: Nil
      testJob2.deps = testJob3 :: Nil
      testJob3.deps = testJob1 :: Nil
      jobQueue.queueJob(testJob4)
      jobQueue.queueJob(testJob2)
      jobQueue.queueJob(testJob1)
      jobQueue.queueJob(testJob3)
      // TODO: uncomment once cycle detection is implemented, it OOMs at the moment
//      val stats = jobQueue.processJobQueue(memberListOnly = false)
//      assert(testJob1.isCompleted)
//      assert(testJob2.isCompleted)
//      assert(stats.processedJobs == 4)
//      assert(stats.dependencyMisses == 1)
    }
  }

  private class TestJob(var deps: List[TestJob] = Nil, var spawnedJobs: List[TestJob] = Nil) extends QueueJob {
    override def complete(memberListOnly: Boolean)(implicit ctx: Context): QueueJob.JobResult = {
      val blocking = deps.find(dep => !dep.isCompleted)
      blocking.map(IncompleteResult).getOrElse {
        completed = true
        // TODO: implement for real
        val spawnedJobsJava = new util.ArrayList[QueueJob]()
        spawnedJobsJava.addAll(spawnedJobs.asJava)
        CompleteResult(spawnedJobsJava)
      }
    }

    private var completed: Boolean = false
    override def isCompleted: Boolean = completed

    override val queueStore: QueueJobStore = new QueueJobStore
  }
}
