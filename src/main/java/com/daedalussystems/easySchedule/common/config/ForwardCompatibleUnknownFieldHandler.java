package com.daedalussystems.easySchedule.common.config;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ForwardCompatibleUnknownFieldHandler extends DeserializationProblemHandler {
    private static final Logger log = LoggerFactory.getLogger(ForwardCompatibleUnknownFieldHandler.class);
    private final double sampleRate;

    public ForwardCompatibleUnknownFieldHandler(
            @Value("${compat.request-unknown-fields.log-sample-rate:0.1}") double sampleRate) {
        this.sampleRate = Math.max(0.0d, Math.min(1.0d, sampleRate));
    }

    @Override
    public boolean handleUnknownProperty(DeserializationContext ctxt,
                                         JsonParser p,
                                         JsonDeserializer<?> deserializer,
                                         Object beanOrClass,
                                         String propertyName) throws IOException {
        Class<?> targetClass = resolveTargetClass(beanOrClass);
        if (targetClass == null || !ForwardCompatibleRequest.class.isAssignableFrom(targetClass)) {
            return false;
        }

        if (log.isDebugEnabled() && shouldSample()) {
            log.debug("request_unknown_field_ignored endpoint={} dtoType={} field={} correlationId={}",
                    resolveEndpoint(),
                    targetClass.getSimpleName(),
                    propertyName,
                    resolveCorrelationId());
        }
        p.skipChildren();
        return true;
    }

    private boolean shouldSample() {
        return sampleRate >= 1.0d || ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    private static Class<?> resolveTargetClass(Object beanOrClass) {
        if (beanOrClass instanceof Class<?> clazz) {
            return clazz;
        }
        return beanOrClass == null ? null : beanOrClass.getClass();
    }

    private static String resolveEndpoint() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            if (request != null) {
                return request.getMethod() + " " + request.getRequestURI();
            }
        }
        return "unknown";
    }

    private static String resolveCorrelationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? "" : correlationId;
    }
}
