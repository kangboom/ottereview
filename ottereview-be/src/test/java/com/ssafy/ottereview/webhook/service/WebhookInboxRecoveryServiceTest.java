package com.ssafy.ottereview.webhook.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.ottereview.webhook.config.WebhookInboxRecoveryProperties;
import com.ssafy.ottereview.webhook.entity.WebhookInboxStatus;
import com.ssafy.ottereview.webhook.repository.WebhookInboxRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WebhookInboxRecoveryServiceTest {

    @Mock
    private WebhookInboxRepository webhookInboxRepository;

    @Mock
    private WebhookInboxRecoveryTransactionService recoveryTransactionService;

    @Mock
    private WebhookInboxRetryWorker retryWorker;

    private final WebhookInboxRecoveryProperties properties = new WebhookInboxRecoveryProperties();
    private WebhookInboxRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        properties.setProcessingTimeout(Duration.ofMinutes(10));
        properties.setBatchSize(100);
        recoveryService = new WebhookInboxRecoveryService(
                webhookInboxRepository,
                properties,
                recoveryTransactionService,
                retryWorker
        );
    }

    @Test
    void recoversStaleRowsBeforeRetryingDueFailures() {
        when(webhookInboxRepository.findStaleIds(
                eq(WebhookInboxStatus.PROCESSING), any(), any(Pageable.class)))
                .thenReturn(List.of(1L));
        when(recoveryTransactionService.recoverStale(eq(1L), any(), any()))
                .thenReturn(true);
        when(webhookInboxRepository.findRetryableIds(
                eq(WebhookInboxStatus.FAILED), any(), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));

        recoveryService.recover();

        verify(recoveryTransactionService).recoverStale(eq(1L), any(), any());
        verify(retryWorker).retry(eq(1L), any());
        verify(retryWorker).retry(eq(2L), any());
    }
}
