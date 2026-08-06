package com.ssafy.ottereview.webhook.service;

import com.ssafy.ottereview.webhook.config.WebhookInboxRecoveryProperties;
import com.ssafy.ottereview.webhook.entity.WebhookInboxStatus;
import com.ssafy.ottereview.webhook.repository.WebhookInboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookInboxRecoveryService {

    private final WebhookInboxRepository webhookInboxRepository;
    private final WebhookInboxRecoveryProperties properties;
    private final WebhookInboxRecoveryTransactionService recoveryTransactionService;
    private final WebhookInboxRetryWorker retryWorker;

    public void recover() {
        LocalDateTime now = LocalDateTime.now();
        recoverStaleProcessing(now);
        retryFailedDeliveries(now);
    }

    private void recoverStaleProcessing(LocalDateTime now) {
        LocalDateTime threshold = now.minus(properties.getProcessingTimeout());
        List<Long> staleIds = webhookInboxRepository.findStaleIds(
                WebhookInboxStatus.PROCESSING,
                threshold,
                PageRequest.of(0, properties.getBatchSize())
        );

        long recoveredCount = staleIds.stream()
                .filter(id -> recoveryTransactionService.recoverStale(id, threshold, now))
                .count();
        if (recoveredCount > 0) {
            log.warn("멈춘 Webhook Inbox를 복구했습니다. count: {}", recoveredCount);
        }
    }

    private void retryFailedDeliveries(LocalDateTime now) {
        List<Long> retryableIds = webhookInboxRepository.findRetryableIds(
                WebhookInboxStatus.FAILED,
                now,
                PageRequest.of(0, properties.getBatchSize())
        );
        retryableIds.forEach(id -> retryWorker.retry(id, now));
    }
}
