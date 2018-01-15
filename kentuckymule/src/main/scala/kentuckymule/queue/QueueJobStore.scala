package kentuckymule.queue

/**
  * A stub for a store associated with a single queue job. In the future we can store here
  * a job continuation or a whole stack of jobs that are blocked on a given job.
  */
final class QueueJobStore {
  var pendingJobs: java.util.ArrayList[QueueJob] = _
}
