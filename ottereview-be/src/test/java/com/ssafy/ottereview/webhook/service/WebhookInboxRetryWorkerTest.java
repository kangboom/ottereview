package com.ssafy.ottereview.webhook.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookInboxRetryWorkerTest {

    @Mock
    private WebhookInboxRecoveryTransactionService recoveryTransactionService;

    @Mock
    private WebhookInboxProcessingService processingService;

    @Mock
    private WebhookInboxTransactionService inboxTransactionService;

    @InjectMocks
    private WebhookInboxRetryWorker retryWorker;

    private final LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0);

    @Test
    void processesInboxOnlyAfterSuccessfulClaim() {
        when(recoveryTransactionService.claimRetry(1L, now)).thenReturn(true);

        retryWorker.retry(1L, now);

        verify(processingService).process(1L);
    }

    @Test
    void skipsInboxClaimedByAnotherWorker() {
        when(recoveryTransactionService.claimRetry(1L, now)).thenReturn(false);

        retryWorker.retry(1L, now);

        verify(processingService, never()).process(1L);
    }

    @Test
    void recordsFailureWithoutStoppingRemainingBatch() {
        RuntimeException failure = new RuntimeException("database error");
        when(recoveryTransactionService.claimRetry(1L, now)).thenReturn(true);
        org.mockito.Mockito.doThrow(failure).when(processingService).process(1L);

        retryWorker.retry(1L, now);

        verify(inboxTransactionService).markFailed(1L, failure);
    }
}
