package com.ssafy.ottereview.webhook.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class WebhookInboxTest {

    @Test
    void startsInProcessingStateAndTracksAttempts() {
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{}");

        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.PROCESSING);
        assertThat(inbox.getAttemptCount()).isOne();
        assertThat(inbox.getLastError()).isNull();
    }

    @Test
    void failedDeliveryCanBeRetriedAndCompleted() {
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{}");
        LocalDateTime retryAt = LocalDateTime.of(2026, 8, 6, 12, 1);
        inbox.fail("database error", retryAt);

        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.FAILED);
        assertThat(inbox.getLastError()).isEqualTo("database error");
        assertThat(inbox.getNextRetryAt()).isEqualTo(retryAt);

        inbox.retry();

        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.PROCESSING);
        assertThat(inbox.getAttemptCount()).isEqualTo(2);
        assertThat(inbox.getLastError()).isNull();
        assertThat(inbox.getNextRetryAt()).isNull();

        LocalDateTime completedAt = LocalDateTime.of(2026, 8, 6, 12, 0);
        inbox.succeed(completedAt);

        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.SUCCEEDED);
        assertThat(inbox.getProcessedAt()).isEqualTo(completedAt);
    }

    @Test
    void truncatesFailureMessageToDatabaseColumnLimit() {
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{}");

        inbox.fail("x".repeat(2100), LocalDateTime.now());

        assertThat(inbox.getLastError()).hasSize(2000);
    }

    @Test
    void retriesOnlyAfterScheduledTimeAndBeforeMaximumAttempts() {
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{}");
        LocalDateTime retryAt = LocalDateTime.of(2026, 8, 6, 12, 1);
        inbox.fail("database error", retryAt);

        assertThat(inbox.canRetry(retryAt.minusSeconds(1), 3)).isFalse();
        assertThat(inbox.canRetry(retryAt, 3)).isTrue();

        inbox.retry();
        inbox.fail("database error", retryAt);

        assertThat(inbox.canRetry(retryAt, 2)).isFalse();
    }

    @Test
    void exhaustedDeliveryCannotBeRetriedAutomatically() {
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{}");

        inbox.exhaust("maximum attempts exceeded");

        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.EXHAUSTED);
        assertThat(inbox.getNextRetryAt()).isNull();
        assertThat(inbox.canRetry(LocalDateTime.now(), 3)).isFalse();
    }
}
