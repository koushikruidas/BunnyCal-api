package io.bunnycal.experience.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.billing.entitlement.EntitlementService;
import io.bunnycal.experience.domain.BookingExperience;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import io.bunnycal.form.repository.FormRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The embed snippet is a PUBLIC CONTRACT: customers paste it into their own sites, so a
 * wrong origin here is only discovered by an end user whose booking widget never appears.
 *
 * <p>It was previously hardcoded to {@code https://app.bunnycal.io}, a domain that does not
 * resolve, which made every copied snippet dead on arrival.
 */
class EmbedSnippetTest {

    private final BookingExperienceRepository experienceRepository = mock(BookingExperienceRepository.class);
    private final EventTypeRepository eventTypeRepository = mock(EventTypeRepository.class);
    private final FormRepository formRepository = mock(FormRepository.class);
    private final EntitlementService entitlementService = mock(EntitlementService.class);

    private final UUID ownerId = UUID.randomUUID();
    private final UUID experienceId = UUID.randomUUID();

    private BookingExperienceService serviceWithBaseUrl(String baseUrl) {
        BookingExperience experience = BookingExperience.builder()
                .id(experienceId)
                .ownerId(ownerId)
                .slug("weapon-deal")
                .build();
        when(experienceRepository.findByOwnerIdAndIdAndDeletedAtIsNull(ownerId, experienceId))
                .thenReturn(Optional.of(experience));
        return new BookingExperienceService(experienceRepository, eventTypeRepository, formRepository,
                entitlementService, baseUrl);
    }

    @Test
    void snippetPointsAtTheConfiguredWebOrigin() {
        String snippet = serviceWithBaseUrl("https://www.bunnycal.io").getEmbedSnippet(ownerId, experienceId);

        assertTrue(snippet.contains("<script src=\"https://www.bunnycal.io/widget.js\"></script>"),
                "widget.js must load from the web app origin, not the API:\n" + snippet);
    }

    @Test
    void snippetDoesNotReferenceTheOldUnresolvableDomain() {
        String snippet = serviceWithBaseUrl("https://www.bunnycal.io").getEmbedSnippet(ownerId, experienceId);

        assertFalse(snippet.contains("app.bunnycal.io"),
                "app.bunnycal.io does not resolve; snippets using it silently fail for customers");
    }

    @Test
    void snippetWorksAgainstALocalDevOrigin() {
        // Copying a snippet from the local dashboard must produce something testable.
        String snippet = serviceWithBaseUrl("http://localhost:5173").getEmbedSnippet(ownerId, experienceId);

        assertTrue(snippet.contains("<script src=\"http://localhost:5173/widget.js\"></script>"), snippet);
    }

    @Test
    void trailingSlashOnBaseUrlDoesNotProduceADoubleSlash() {
        String snippet = serviceWithBaseUrl("https://www.bunnycal.io/").getEmbedSnippet(ownerId, experienceId);

        assertTrue(snippet.contains("https://www.bunnycal.io/widget.js"), snippet);
        assertFalse(snippet.contains("//widget.js"), "double slash would 404:\n" + snippet);
    }

    @Test
    void snippetCarriesTheExperienceSlugAndContainerId() {
        String snippet = serviceWithBaseUrl("https://www.bunnycal.io").getEmbedSnippet(ownerId, experienceId);

        assertTrue(snippet.contains("experienceSlug: 'weapon-deal'"), snippet);
        assertTrue(snippet.contains("<div id=\"bunnycal-widget\"></div>"), snippet);
        assertTrue(snippet.contains("containerId: 'bunnycal-widget'"), snippet);
    }

    @Test
    void scriptTagPrecedesTheInitCall() {
        // BunnyCal.init is only defined once widget.js has executed.
        String snippet = serviceWithBaseUrl("https://www.bunnycal.io").getEmbedSnippet(ownerId, experienceId);

        assertTrue(snippet.indexOf("widget.js") < snippet.indexOf("BunnyCal.init"),
                "the SDK must load before init is called:\n" + snippet);
    }

    @Test
    void containerDivPrecedesTheInitCall() {
        // init() looks the container up by id immediately, so the div must already exist.
        String snippet = serviceWithBaseUrl("https://www.bunnycal.io").getEmbedSnippet(ownerId, experienceId);

        assertTrue(snippet.indexOf("id=\"bunnycal-widget\"") < snippet.indexOf("BunnyCal.init"),
                "container must be in the DOM before init runs:\n" + snippet);
    }

    @Test
    void blankBaseUrlProducesARootRelativeScriptRatherThanTheLiteralText() {
        // Misconfiguration should degrade to a same-origin path, not "null/widget.js".
        String snippet = serviceWithBaseUrl("").getEmbedSnippet(ownerId, experienceId);

        assertTrue(snippet.contains("src=\"/widget.js\""), snippet);
        assertFalse(snippet.contains("null"), snippet);
    }

    @Test
    void snippetIsStableAcrossCalls() {
        BookingExperienceService service = serviceWithBaseUrl("https://www.bunnycal.io");

        assertEquals(service.getEmbedSnippet(ownerId, experienceId),
                service.getEmbedSnippet(ownerId, experienceId));
    }
}
