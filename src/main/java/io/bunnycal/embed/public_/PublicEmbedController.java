package io.bunnycal.embed.public_;

import io.bunnycal.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/embed")
public class PublicEmbedController {

    private final EmbedQueryService embedQueryService;

    public PublicEmbedController(EmbedQueryService embedQueryService) {
        this.embedQueryService = embedQueryService;
    }

    @GetMapping("/{experienceSlug}")
    public ResponseEntity<ApiResponse<PublicEmbedConfigResponse>> embedConfig(
            @PathVariable String experienceSlug) {
        return ResponseEntity.ok(ApiResponse.success(embedQueryService.getEmbedConfig(experienceSlug)));
    }
}
