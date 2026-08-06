package com.ssafy.ottereview.webhook.service;

import com.ssafy.ottereview.webhook.config.WebhookInboxRecoveryProperties;
import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import com.ssafy.ottereview.webhook.repository.WebhookInboxRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebhookInboxRecoveryTransactionService {

    private static final String PROCESSING_TIMEOUT_MESSAGE = "Webhook processing timeout";

    private final WebhookInboxRepository webhookInboxRepository;
    private final WebhookInboxRecoveryProperties properties;
    private final WebhookRetryPolicy webhookRetryPolicy;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverStale(Long inboxId, LocalDateTime threshold, LocalDateTime now) {
        WebhookInbox inbox = webhookInboxRepository.findByIdForUpdate(inboxId).orElse(null);
        if (inbox == null) {
            return false;
        }

        if (!inbox.isProcessing()
                || inbox.getModifiedAt() == null
                || !inbox.getModifiedAt().isBefore(threshold)) {
            return false;
        }

        if (webhookRetryPolicy.attemptsExhausted(inbox)) {
            inbox.exhaust(PROCESSING_TIMEOUT_MESSAGE);
        } else {
            inbox.fail(PROCESSING_TIMEOUT_MESSAGE, now);
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimRetry(Long inboxId, LocalDateTime now) {
        WebhookInbox inbox = webhookInboxRepository.findByIdForUpdate(inboxId).orElse(null);
        if (inbox == null) {
            return false;
        }

        if (!inbox.canRetry(now, properties.getMaxAttempts())) {
            return false;
        }

        inbox.retry();
        return true;
    }
}
