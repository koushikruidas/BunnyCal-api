package io.bunnycal.booking.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingManageLinkServiceTest {

    @Test
    void build_createsCanonicalManageUrl() {
        BookingManageLinkService service = new BookingManageLinkService("https://app.example.com/");
        String url = service.build(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "tok_123",
                "host-user",
                "intro-call"
        );

        assertEquals(
                "https://app.example.com/manage/11111111-1111-1111-1111-111111111111?token=tok_123&u=host-user&e=intro-call",
                url
        );
    }
}
