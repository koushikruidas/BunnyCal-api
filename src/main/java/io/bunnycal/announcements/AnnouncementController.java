package io.bunnycal.announcements;

import io.bunnycal.common.api.ApiResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private final AnnouncementQueryService service;

    public AnnouncementController(AnnouncementQueryService service) {
        this.service = service;
    }

    @GetMapping("/active")
    public ApiResponse<List<PublicAnnouncementDto>> active(Authentication authentication) {
        return ApiResponse.success(service.active(authentication));
    }
}
