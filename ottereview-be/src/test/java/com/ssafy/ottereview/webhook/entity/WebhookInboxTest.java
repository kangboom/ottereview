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
        inbox.fail("database error");

        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.FAILED);
        assertThat(inbox.getLastError()).isEqualTo("database error");

        inbox.retry();

        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.PROCESSING);
        assertThat(inbox.getAttemptCount()).isEqualTo(2);
        assertThat(inbox.getLastError()).isNull();

        LocalDateTime completedAt = LocalDateTime.of(2026, 8, 6, 12, 0);
        inbox.succeed(completedAt);

        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.SUCCEEDED);
        assertThat(inbox.getProcessedAt()).isEqualTo(completedAt);
    }

    @Test
    void truncatesFailureMessageToDatabaseColumnLimit() {
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{}");

        inbox.fail("x".repeat(2100));

        assertThat(inbox.getLastError()).hasSize(2000);
    }
}
