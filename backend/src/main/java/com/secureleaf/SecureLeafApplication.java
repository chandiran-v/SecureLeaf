package com.secureleaf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SecureLeaf — DRM-protected digital content marketplace.
 *
 * {@code @EnableAsync} activates the ThreadPoolTaskExecutor declared in AsyncConfig,
 * used by {@code @Async("contentProcessingExecutor")} methods in the pipeline.
 * {@code @EnableScheduling} activates the {@code @Scheduled} job poller in
 * ProcessingJobWorker that polls for QUEUED jobs every 5 seconds.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableJpaAuditing
public class SecureLeafApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureLeafApplication.class, args);
    }
}
