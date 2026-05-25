package com.daedalussystems.easySchedule.availability.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CreateEventTypeRequestDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void availabilityCalendarConnectionId_acceptsRawStringAtDtoBoundary() throws Exception {
        String json = """
                {
                  "name":"Intro",
                  "durationMinutes":30,
                  "bufferBeforeMinutes":0,
                  "bufferAfterMinutes":0,
                  "slotIntervalMinutes":30,
                  "minNoticeMinutes":0,
                  "maxAdvanceDays":30,
                  "holdDurationMinutes":10,
                  "availabilityCalendars":[
                    {"connectionId":"google","provider":"google","externalCalendarId":"primary"}
                  ],
                  "conference":{"enabled":false}
                }
                """;

        CreateEventTypeRequest request = mapper.readValue(json, CreateEventTypeRequest.class);
        assertEquals("google", request.availabilityCalendars().get(0).connectionId());
    }
}

