package com.ssafy.ottereview.webhook.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookInboxRetryWorker {

    private final WebhookInboxRecoveryTransactionService recoveryTransactionService;
    private final WebhookInboxProcessingService processingService;
    private final WebhookInboxTransactionService inboxTransactionService;

    public void retry(Long inboxId, LocalDateTime now) {
        if (!recoveryTransactionService.claimRetry(inboxId, now)) {
            return;
        }

        try {
            processingService.process(inboxId);
        } catch (RuntimeException exception) {
            inboxTransactionService.markFailed(inboxId, exception);
            log.warn("Webhook Inbox 자동 재시도에 실패했습니다. inboxId: {}", inboxId, exception);
        }
    }
}
