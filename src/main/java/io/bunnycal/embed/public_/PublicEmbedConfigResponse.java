package io.bunnycal.embed.public_;

import io.bunnycal.booking.dto.PublicEventInfoResponse;
import io.bunnycal.form.dto.QuestionResponse;
import java.util.List;

public record PublicEmbedConfigResponse(
        PublicEventInfoResponse eventInfo,
        List<QuestionResponse> form,
        String primaryColor,
        boolean showBranding,
        String embedToken,
        String eventTypeSlug    // needed by embed SPA to drive slot availability queries
) {}
