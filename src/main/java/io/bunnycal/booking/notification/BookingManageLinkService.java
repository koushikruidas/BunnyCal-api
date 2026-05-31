package io.bunnycal.booking.notification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BookingManageLinkService {

    private final String publicBaseUrl;

    public BookingManageLinkService(@Value("${app.public-base-url:http://localhost:5173}") String publicBaseUrl) {
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
    }

    public String build(UUID bookingId, String token, String username, String eventTypeSlug) {
        return publicBaseUrl
                + "/manage/"
                + encodePathSegment(bookingId == null ? "" : bookingId.toString())
                + "?token=" + encodeQueryValue(token)
                + "&u=" + encodeQueryValue(username)
                + "&e=" + encodeQueryValue(eventTypeSlug);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
