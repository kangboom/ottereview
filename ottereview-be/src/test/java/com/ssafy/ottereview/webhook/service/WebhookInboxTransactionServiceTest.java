package com.ssafy.ottereview.webhook.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import com.ssafy.ottereview.webhook.repository.WebhookInboxRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookInboxTransactionServiceTest {

    @Mock
    private WebhookInboxRepository webhookInboxRepository;

    @Mock
    private WebhookRetryPolicy webhookRetryPolicy;

    @Mock
    private WebhookInbox inbox;

    private WebhookInboxTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new WebhookInboxTransactionService(
                webhookInboxRepository,
                webhookRetryPolicy
        );
        when(webhookInboxRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(inbox));
    }

    @Test
    void schedulesNextAttemptWhenRetryBudgetRemains() {
        RuntimeException failure = new RuntimeException("database error");
        LocalDateTime nextRetryAt = LocalDateTime.of(2026, 8, 6, 12, 1);
        when(webhookRetryPolicy.attemptsExhausted(inbox)).thenReturn(false);
        when(webhookRetryPolicy.nextRetryAt(org.mockito.ArgumentMatchers.eq(inbox),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(nextRetryAt);

        transactionService.markFailed(1L, failure);

        verify(inbox).fail("database error", nextRetryAt);
    }

    @Test
    void exhaustsInboxWhenMaximumAttemptsWereUsed() {
        RuntimeException failure = new RuntimeException("database error");
        when(webhookRetryPolicy.attemptsExhausted(inbox)).thenReturn(true);

        transactionService.markFailed(1L, failure);

        verify(inbox).exhaust("database error");
    }
}
