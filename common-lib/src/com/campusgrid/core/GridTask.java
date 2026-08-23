package com.campusgrid.core;

import java.io.Serializable;
import java.util.List;

/**
 * Core contract for a Master-Worker distributed execution model.
 * <p>
 * A {@code GridTask} represents a unit of work that can be split into smaller tasks,
 * executed on worker nodes, and merged into a single aggregated result.
 *
 * @param <T> result type produced by execution and merge operations
 */
public interface GridTask<T> extends Serializable {

    /**
     * Serialization identifier for cross-version compatibility.
     */
    long serialVersionUID = 1L;

    /**
     * Splits this task into {@code n} sub-tasks for parallel execution.
     *
     * @param n number of sub-tasks to create (must be greater than 0)
     * @return list of sub-tasks to distribute to worker nodes
     */
    List<GridTask<T>> split(int n);

    /**
     * Executes this task on a worker node.
     *
     * @return task execution result
     */
    T execute();

    /**
     * Merges results returned from worker nodes into a final result.
     *
     * @param results results produced by distributed sub-task execution
     * @return aggregated final result
     */
    T merge(List<T> results);

    // =========================================================================
    // PHASE 2: ADVANCED EXECUTION CONTRACT
    // =========================================================================

    /**
     * Phase 2 execution supporting localized context (file paths) and real-time 
     * progress tracking for the dashboard API.
     * <p>
     * By default, this falls back to the Phase 1 {@code execute()} method so legacy 
     * tasks (like Mandelbrot) do not break during transition.
     *
     * @param context Provides the Agent's local working directory and job metadata
     * @param reporter Callback to stream progress to the Master node's dashboard
     * @return task execution result
     */
    default T execute(TaskContext context, ProgressReporter reporter) {
        if (reporter != null) {
            reporter.reportProgress(0.0, "Starting legacy Phase 1 task...");
        }
        
        T result = execute();
        
        if (reporter != null) {
            reporter.reportProgress(100.0, "Legacy Phase 1 task complete.");
        }
        return result;
    }
}