package io.bunnycal.conferencing;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces that provider-native conferencing fields never escape the adapter boundary.
 *
 * <p>Phase 3C canonical contract: ConferenceDetails is the only conferencing shape
 * allowed downstream of the conferencing adapter layer. Any file that references
 * forbidden field names outside the allowed adapter packages fails immediately.
 */
class ConferencingBoundaryArchitectureTest {

    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "hangoutLink",
            "joinWebUrl",
            "conferenceData"
    );

    private static final List<String> ADAPTER_PACKAGES = List.of(
            "src/main/java/io/bunnycal/calendar/client/",
            "src/main/java/io/bunnycal/calendar/provider/",
            "src/main/java/io/bunnycal/calendar/replay/"
    );

    private static final List<String> FORBIDDEN_DOWNSTREAM_PATHS = List.of(
            "src/main/java/io/bunnycal/booking/",
            "src/main/java/io/bunnycal/availability/",
            "src/main/java/io/bunnycal/sync/",
            "src/main/java/io/bunnycal/conferencing/service/ConferencingOrchestrator.java"
    );

    @Test
    void dtoPayloads_doNotExposeRawConferenceUrl() throws IOException {
        String meetingSummary = Files.readString(
                Path.of("src/main/java/io/bunnycal/booking/dto/MeetingSummaryResponse.java"));
        assertFalse(meetingSummary.contains("String conferenceUrl"),
                "MeetingSummaryResponse must not expose raw conferenceUrl field");

        String publicManage = Files.readString(
                Path.of("src/main/java/io/bunnycal/booking/dto/PublicManageBookingResponse.java"));
        assertFalse(publicManage.contains("String conferenceUrl"),
                "PublicManageBookingResponse must not expose raw conferenceUrl field");
    }

    @Test
    void forbiddenProviderFields_doNotLeakBeyondAdapterBoundary() throws IOException {
        for (String downstreamPath : FORBIDDEN_DOWNSTREAM_PATHS) {
            Path root = Path.of(downstreamPath);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> javaFiles = walk
                        .filter(p -> p.toString().endsWith(".java"))
                        .toList();
                for (Path file : javaFiles) {
                    String content = Files.readString(file);
                    for (String forbidden : FORBIDDEN_FIELDS) {
                        assertFalse(content.contains(forbidden),
                                "Forbidden provider-native conferencing field '" + forbidden
                                        + "' found outside adapter boundary in: " + file);
                    }
                }
            }
        }
    }

    @Test
    void notifications_doNotBranchOnConferencingProvider() throws IOException {
        String notificationService = Files.readString(
                Path.of("src/main/java/io/bunnycal/booking/notification/BookingNotificationService.java"));
        assertFalse(notificationService.contains("GOOGLE_MEET"),
                "BookingNotificationService must not branch on GOOGLE_MEET");
        assertFalse(notificationService.contains("MICROSOFT_TEAMS"),
                "BookingNotificationService must not branch on MICROSOFT_TEAMS");
        assertFalse(notificationService.contains("joinWebUrl"),
                "BookingNotificationService must not reference joinWebUrl");
        assertFalse(notificationService.contains("hangoutLink"),
                "BookingNotificationService must not reference hangoutLink");

        String icsGenerator = Files.readString(
                Path.of("src/main/java/io/bunnycal/booking/notification/IcsInviteGenerator.java"));
        assertFalse(icsGenerator.contains("GOOGLE_MEET"),
                "IcsInviteGenerator must not branch on GOOGLE_MEET");
        assertFalse(icsGenerator.contains("MICROSOFT_TEAMS"),
                "IcsInviteGenerator must not branch on MICROSOFT_TEAMS");
        assertFalse(icsGenerator.contains("hangoutLink"),
                "IcsInviteGenerator must not reference hangoutLink");
        assertFalse(icsGenerator.contains("joinWebUrl"),
                "IcsInviteGenerator must not reference joinWebUrl");
    }

    @Test
    void conferencingApiDtos_exposeOnlyCanonicalShape() throws IOException {
        String response = Files.readString(
                Path.of("src/main/java/io/bunnycal/booking/dto/ConferenceDetailsResponse.java"));
        assertFalse(response.contains("hangoutLink"),
                "ConferenceDetailsResponse must not contain hangoutLink");
        assertFalse(response.contains("joinWebUrl"),
                "ConferenceDetailsResponse must not contain joinWebUrl");
        assertFalse(response.contains("conferenceData"),
                "ConferenceDetailsResponse must not contain conferenceData");
    }
}
