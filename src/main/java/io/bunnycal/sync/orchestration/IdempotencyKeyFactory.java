package io.bunnycal.sync.orchestration;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyFactory {

    public String build(String provider, UUID internalRefId) {
        return provider + ":" + internalRefId;
    }
}
