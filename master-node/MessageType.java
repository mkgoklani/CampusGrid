/**
 * CAMPUS GRID - MESSAGE TYPE ENUM
 * 
 * Defines the protocol message types transmitted across the TCP object stream
 * between the Master Node and Agent Nodes:
 * - HEARTBEAT: Periodic hardware health and telemetry updates from workers.
 * - SUBMIT_TASK: Workload task assignment dispatched from Master to Worker.
 * - TASK_PROGRESS: Incremental completion percentage and status updates from Worker.
 * - CANCEL_TASK: Execution cancellation or poison pill from Master.
 * - EVICTED: Notification that worker PC was reclaimed due to user interaction.
 * - TASK_COMPLETE: Final computation result and output data packet.
 */
public enum MessageType {
    HEARTBEAT,
    SUBMIT_TASK,
    TASK_PROGRESS,
    CANCEL_TASK,
    EVICTED,
    TASK_COMPLETE
}
