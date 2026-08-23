/**
 * CAMPUS GRID - WORKER STATUS ENUM
 * 
 * Defines the lifecycle and operational states of a distributed Agent node:
 * - IDLE: Connected and waiting for computational task assignments.
 * - BUSY: Currently executing a computational task slice.
 * - OFFLINE: Socket disconnected or failed to respond within timeout limits.
 * - EVICTED: Worker agent reclaimed dynamically due to user keyboard/mouse activity.
 */
public enum WorkerStatus {
    IDLE,
    BUSY,
    OFFLINE,
    EVICTED
}
