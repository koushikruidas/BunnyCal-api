package io.bunnycal.session.dto;

/**
 * Result of a bulk series operation.
 *
 * <p>{@code affectedCount} is what actually happened, not what was requested — sessions
 * already in a terminal state are skipped, so the two can differ.
 */
public record SeriesOperationResponse(int affectedCount, String message) {}
