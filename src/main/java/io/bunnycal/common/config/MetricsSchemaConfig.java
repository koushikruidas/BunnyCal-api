package io.bunnycal.common.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsSchemaConfig {

    @Bean
    MeterFilter prometheusTagSchemaNormalizer() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if ("jdbc_query_active_seconds".equals(id.getName())) {
                    return ensureTag(id, "db_collection_name", "unknown");
                }
                return id;
            }
        };
    }

    private static Meter.Id ensureTag(Meter.Id id, String key, String defaultValue) {
        List<Tag> existing = id.getTags();
        for (Tag tag : existing) {
            if (key.equals(tag.getKey())) {
                if (tag.getValue() != null && !tag.getValue().isBlank()) {
                    return id;
                }
                List<Tag> replaced = new ArrayList<>(existing.size());
                for (Tag t : existing) {
                    if (key.equals(t.getKey())) {
                        replaced.add(Tag.of(key, defaultValue));
                    } else {
                        replaced.add(t);
                    }
                }
                return id.withTags(replaced);
            }
        }
        List<Tag> expanded = new ArrayList<>(existing.size() + 1);
        expanded.addAll(existing);
        expanded.add(Tag.of(key, defaultValue));
        return id.withTags(expanded);
    }
}
