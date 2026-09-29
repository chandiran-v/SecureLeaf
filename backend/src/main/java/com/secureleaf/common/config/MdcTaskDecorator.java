package com.secureleaf.common.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

import java.util.Map;

/**
 * Phase 9, D1 — copies the calling thread's MDC (currently just {@code correlationId}) onto the
 * pool thread that actually runs an {@code @Async} task.
 *
 * WHY THIS IS NEEDED
 * MDC is backed by a {@link ThreadLocal}. {@code CorrelationIdFilter} sets it on the Tomcat
 * request thread, but {@code @Async} hands the task to a *different*, pre-existing thread from a
 * {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor} — a plain thread that
 * has never heard of this request's correlation id. Without this decorator, every log line
 * written from inside an {@code @Async} method (e.g. the content processing pipeline, purchase
 * notification emails) would have no correlation id at all, breaking the "trace one request
 * end-to-end through the logs" promise D1 exists for.
 *
 * The pool thread is reused for whatever task comes next, so the calling thread's MDC is restored
 * (or cleared, if the pool thread had none) once this task finishes — otherwise a later, unrelated
 * task on the same pool thread would inherit this request's correlation id.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    @NonNull
    public Runnable decorate(@NonNull Runnable runnable) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            if (callerContext != null) {
                MDC.setContextMap(callerContext);
            }
            try {
                runnable.run();
            } finally {
                if (previousContext != null) {
                    MDC.setContextMap(previousContext);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
