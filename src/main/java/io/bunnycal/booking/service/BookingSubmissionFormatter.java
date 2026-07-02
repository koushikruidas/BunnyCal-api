package io.bunnycal.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.dto.QuestionnaireResponse;
import io.bunnycal.embed.public_.BookingQuestionAnswer;
import io.bunnycal.session.domain.SessionRegistration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BookingSubmissionFormatter {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public BookingSubmissionFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<QuestionnaireResponse> toResponses(List<BookingQuestionAnswer> answers) {
        if (answers == null || answers.isEmpty()) {
            return List.of();
        }
        List<QuestionnaireResponse> responses = new ArrayList<>();
        for (BookingQuestionAnswer answer : answers) {
            String value = formatAnswerValue(answer);
            if (value == null || value.isBlank()) {
                continue;
            }
            responses.add(new QuestionnaireResponse(answer.getQuestionLabelSnapshot(), value));
        }
        return responses;
    }

    public String buildBookingDescription(Booking booking, List<QuestionnaireResponse> responses) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "Invitee Name", booking.getGuestName());
        appendLine(builder, "Invitee Email", booking.getGuestEmail());
        appendLine(builder, "Notes", booking.getGuestNotes());
        appendResponses(builder, responses);
        return builder.toString().trim();
    }

    public String buildSessionRegistrationDescription(String guestName,
                                                      String guestEmail,
                                                      String guestNotes) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "Invitee Name", guestName);
        appendLine(builder, "Invitee Email", guestEmail);
        appendLine(builder, "Notes", guestNotes);
        return builder.toString().trim();
    }

    public String buildSessionDescription(List<SessionRegistration> registrations) {
        if (registrations == null || registrations.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Attendees:");
        for (SessionRegistration registration : registrations) {
            builder.append("\n");
            builder.append("- ");
            builder.append(firstNonBlank(registration.getGuestName(), "Guest"));
            String email = registration.getGuestEmail();
            if (email != null && !email.isBlank()) {
                builder.append(" <").append(email.trim()).append(">");
            }
            if (registration.getGuestNotes() != null && !registration.getGuestNotes().isBlank()) {
                builder.append("\n  Notes: ").append(registration.getGuestNotes().trim());
            }
        }
        return builder.toString().trim();
    }

    private String formatAnswerValue(BookingQuestionAnswer answer) {
        if (answer.getAnswerValue() != null && !answer.getAnswerValue().isBlank()) {
            return answer.getAnswerValue().trim();
        }
        if (answer.getAnswerJson() == null || answer.getAnswerJson().isBlank()) {
            return null;
        }
        try {
            List<String> options = objectMapper.readValue(answer.getAnswerJson(), STRING_LIST);
            return options.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse(null);
        } catch (Exception ignored) {
            return answer.getAnswerJson().trim();
        }
    }

    private static void appendResponses(StringBuilder builder, List<QuestionnaireResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append("Responses:");
        for (QuestionnaireResponse response : responses) {
            builder.append("\n");
            builder.append(response.questionLabel() == null ? "Question" : response.questionLabel().trim());
            builder.append(": ");
            builder.append(response.answerValue());
        }
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n");
        }
        builder.append(label);
        builder.append(": ");
        builder.append(value.trim());
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
