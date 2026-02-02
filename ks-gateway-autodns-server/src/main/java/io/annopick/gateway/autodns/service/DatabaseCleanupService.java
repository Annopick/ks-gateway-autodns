package io.annopick.gateway.autodns.service;

import io.annopick.gateway.autodns.config.AutoDnsProperties;
import io.annopick.gateway.autodns.repository.ApisixOperationRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseCleanupService {

    private final ApisixOperationRepository apisixOperationRepository;
    private final AutoDnsProperties properties;

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void cleanupOldRecords() {
        try {
            LocalDateTime cutoffDate = LocalDateTime.now()
                .minusDays(properties.getDatabase().getRecordRetentionDays());
            
            apisixOperationRepository.deleteOlderThan(cutoffDate);
            log.info("Cleaned up APISIX operation records older than {}", cutoffDate);
        } catch (Exception e) {
            log.error("Failed to cleanup old records", e);
        }
    }
}
