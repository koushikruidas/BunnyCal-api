package io.bunnycal.availability.controller;

import io.bunnycal.availability.dto.SlotRequest;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.service.SlotService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/event-types/{eventTypeId}/slots")
public class SlotController {

    private final SlotService slotService;

    public SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<SlotResponse>> getSlots(
            @PathVariable("userId") UUID userId,
            @PathVariable("eventTypeId") UUID eventTypeId,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "date is required.");
        }
        SlotResponse response = slotService.getSlots(new SlotRequest(userId, eventTypeId, date));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
