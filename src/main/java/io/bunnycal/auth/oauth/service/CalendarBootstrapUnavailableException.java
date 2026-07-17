package io.bunnycal.auth.oauth.service;

/**
 * The host signed in successfully, but their calendar could not be connected for a reason the user
 * can fix themselves — denied consent, an organization policy block, or no offline access on record.
 *
 * <p>Distinct from an unexpected fault so sign-in can log it as an expected outcome rather than an
 * error: onboarding shows a reconnect action, and the user recovers in one click.
 */
public class CalendarBootstrapUnavailableException extends RuntimeException {
    public CalendarBootstrapUnavailableException(String message) {
        super(message);
    }
}
