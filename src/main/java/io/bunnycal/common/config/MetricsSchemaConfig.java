package io.bunnycal.common.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsSchemaConfig {

    @Bean
    MeterFilter prometheusTagSchemaNormalizer() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (id.getName().startsWith("jdbc_query_") || id.getName().startsWith("jdbc.query.")) {
                    // Normalize JDBC instrumentation series to a stable schema across
                    // drivers/conventions: some paths emit db_collection_name, others don't.
                    // Drop it uniformly to prevent Prometheus tag-key collisions.
                    Meter.Id normalized = removeTag(id, "db_collection_name");
                    normalized = removeTag(normalized, "db.collection.name");
                    return normalized;
                }
                return id;
            }
        };
    }

    @Bean
    static BeanPostProcessor prometheusRegistryMeterFilterPostProcessor(MeterFilter prometheusTagSchemaNormalizer) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof PrometheusMeterRegistry registry) {
                    registry.config().meterFilter(prometheusTagSchemaNormalizer);
                }
                return bean;
            }
        };
    }

    private static Meter.Id removeTag(Meter.Id id, String key) {
        List<Tag> existing = id.getTags();
        List<Tag> filtered = new ArrayList<>(existing.size());
        boolean removed = false;
        for (Tag t : existing) {
            if (key.equals(t.getKey())) {
                removed = true;
                continue;
            }
            filtered.add(t);
        }
        return removed ? id.withTags(filtered) : id;
    }
}
