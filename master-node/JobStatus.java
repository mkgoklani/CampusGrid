/**
 * CAMPUS GRID - JOB STATUS ENUM
 * 
 * Represents the lifecycle states of a submitted distributed compute or render job:
 * - QUEUED: Job submitted and waiting in queue for available worker resources.
 * - RUNNING: Sub-tasks are actively being dispatched and computed across worker nodes.
 * - COMPLETED: All sub-tasks (frame ranges/data strips) completed and verified.
 * - FAILED: Job execution aborted due to unrecoverable errors or worker timeout limits.
 * - CANCELLED: Job explicitly aborted by operator via Master console / CLI.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
