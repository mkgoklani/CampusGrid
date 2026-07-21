package com.campusgrid.agent.util;

/**
 * Defines retry policies and backoff strategies for transient operation failures.
 * <p>
 * Primarily used by the network layer to handle reconnection attempts to the Master node
 * when network connection drops occur.
 * </p>
 */
public class RetryPolicy {
    // TODO: Define attributes for max retry attempts, base backoff time, and multiplier.
    // TODO: Implement exponential backoff sleep calculation with optional jitter.
    // TODO: Implement a helper to execute Runnable or Callable tasks under this retry policy.
}
