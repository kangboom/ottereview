package com.ssafy.ottereview.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.ottereview.webhook.config.WebhookInboxRecoveryProperties;
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
class WebhookInboxRecoveryTransactionServiceTest {

    @Mock
    private WebhookInboxRepository webhookInboxRepository;

    @Mock
    private WebhookRetryPolicy webhookRetryPolicy;

    @Mock
    private WebhookInbox inbox;

    private final WebhookInboxRecoveryProperties properties = new WebhookInboxRecoveryProperties();
    private WebhookInboxRecoveryTransactionService transactionService;

    @BeforeEach
    void setUp() {
        properties.setMaxAttempts(3);
        transactionService = new WebhookInboxRecoveryTransactionService(
                webhookInboxRepository,
                properties,
                webhookRetryPolicy
        );
    }

    @Test
    void recoversOnlyInboxThatIsStillStaleAndProcessing() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0);
        LocalDateTime threshold = now.minusMinutes(10);
        when(webhookInboxRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(inbox));
        when(inbox.isProcessing()).thenReturn(true);
        when(inbox.getModifiedAt()).thenReturn(threshold.minusSeconds(1));
        when(webhookRetryPolicy.attemptsExhausted(inbox)).thenReturn(false);

        boolean recovered = transactionService.recoverStale(1L, threshold, now);

        assertThat(recovered).isTrue();
        verify(inbox).fail("Webhook processing timeout", now);
    }

    @Test
    void doesNotRecoverInboxThatCompletedAfterCandidateQuery() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0);
        LocalDateTime threshold = now.minusMinutes(10);
        when(webhookInboxRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(inbox));
        when(inbox.isProcessing()).thenReturn(false);

        boolean recovered = transactionService.recoverStale(1L, threshold, now);

        assertThat(recovered).isFalse();
        verify(inbox, never()).fail("Webhook processing timeout", now);
    }

    @Test
    void onlyOneWorkerCanClaimRetryableInboxAfterStateRecheck() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0);
        when(webhookInboxRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(inbox));
        when(inbox.canRetry(now, 3)).thenReturn(true, false);

        boolean firstClaim = transactionService.claimRetry(1L, now);
        boolean secondClaim = transactionService.claimRetry(1L, now);

        assertThat(firstClaim).isTrue();
        assertThat(secondClaim).isFalse();
        verify(inbox).retry();
    }
}
