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
}
