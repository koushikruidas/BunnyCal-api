package io.bunnycal.conferencing.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.conferencing.repository.ZoomConferencingConnectionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a host's default meeting link pointing at something they can actually use.
 *
 * <p>The default provider is a stored choice, but what is *servable* depends on connections that
 * come and go: Google Meet needs the write-back calendar to be Google, Teams needs a Microsoft
 * work/school one, and Zoom needs a live Zoom connection. Whenever one of those changes the stored
 * default can become a promise the system cannot keep, and a booking then either silently loses its
 * join link or fails at confirmation time in front of a guest.
 *
 * <p>The write-back path already handled the calendar-backed half of this. Zoom was missed:
 * disconnecting it deleted the connection and left {@code defaultConferencingProvider = ZOOM}, so
 * new events still selected Zoom by default with nothing behind it.
 *
 * <p>An unservable default stands down to {@code NONE} rather than being swapped for whatever else
 * happens to be connected. Silently substituting would mean bookings switch to a different meeting
 * provider than the host chose; standing down makes the gap visible — new bookings carry no link —
 * so the host replaces it deliberately. Picking the replacement stays a user action, and
 * {@code setDefaultConferencing} now rejects anything they cannot actually use.
 */
@Service
public class DefaultConferencingReconciler {

    private final UserRepository userRepository;
    private final CalendarConnectionRepository connectionRepository;
    private final ZoomConferencingConnectionRepository zoomConnectionRepository;
    private final NativeConferencingCapabilityService capabilityService;

    public DefaultConferencingReconciler(UserRepository userRepository,
                                         CalendarConnectionRepository connectionRepository,
                                         ZoomConferencingConnectionRepository zoomConnectionRepository,
                                         NativeConferencingCapabilityService capabilityService) {
        this.userRepository = userRepository;
        this.connectionRepository = connectionRepository;
        this.zoomConnectionRepository = zoomConnectionRepository;
        this.capabilityService = capabilityService;
    }

    /**
     * Re-checks the stored default and replaces it if it can no longer be served.
     *
     * <p>Safe to call after any connect or disconnect; it is a no-op when the current default is
     * still valid. Call it after the change has been persisted, since it reads current state.
     *
     * @param reason short tag recorded in the ops log, e.g. {@code zoom_disconnected}
     */
    @Transactional
    public void reconcile(UUID userId, String reason) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        ConferencingProviderType current = user.getDefaultConferencingProvider();
        if (current == null || canServe(userId, current)) {
            return;
        }
        user.setDefaultConferencingProvider(ConferencingProviderType.NONE);
        userRepository.save(user);
        OpsLoggers.HOST.warn(
                "default_conferencing_stood_down hostId={} previous={} reason={}",
                userId, current, reason);
    }

    /**
     * Whether this provider can currently produce a meeting link for the host.
     *
     * <p>{@code NONE} is always servable — it is the absence of a link, not a capability.
     * {@code DEFAULT} is a pointer that must be resolved before it reaches here, and
     * {@code CUSTOM_URL} is chosen per event and never stored as a global default; treating both
     * as servable leaves them untouched rather than rewriting a value this class does not own.
     */
    public boolean canServe(UUID userId, ConferencingProviderType provider) {
        if (provider == null) {
            return false;
        }
        return switch (provider) {
            case NONE, DEFAULT, CUSTOM_URL -> true;
            case ZOOM -> zoomConnectionRepository.findByUserId(userId).isPresent();
            case GOOGLE_MEET, MICROSOFT_TEAMS -> capabilityService.canServe(writeback(userId), provider);
        };
    }

    /**
     * The connection bookings are written to, if it can still host a meeting link.
     *
     * <p>Only a connection the user has actually taken away is unusable. FAILED and ERROR are
     * deliberately allowed through, matching {@code OnboardingService.configureCalendar}, which
     * carries the same reasoning at length: a transient sync fault says nothing about whether the
     * account can mint a Meet or Teams link, and the sweep clears it on its own within a minute.
     *
     * <p>Requiring ACTIVE here was the second half of the signup failure. configureCalendar
     * tolerated a briefly-FAILED connection and then called straight into this class, which did
     * not — so a sync hiccup during signup still aborted setup and produced the "reconnect your
     * calendar" notice for a calendar that was fine. A genuinely dead connection is still excluded
     * below, and cannot serve availability either way.
     */
    private CalendarConnection writeback(UUID userId) {
        return connectionRepository.findByUserIdAndDefaultWritebackTrue(userId)
                .filter(c -> c.getStatus() != CalendarConnectionStatus.DISCONNECTED
                        && c.getStatus() != CalendarConnectionStatus.REVOKED)
                .orElse(null);
    }
}
