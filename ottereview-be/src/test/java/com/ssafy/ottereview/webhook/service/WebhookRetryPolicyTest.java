package com.ssafy.ottereview.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ottereview.webhook.config.WebhookInboxRecoveryProperties;
import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookRetryPolicyTest {

    private final WebhookInboxRecoveryProperties properties = new WebhookInboxRecoveryProperties();
    private WebhookRetryPolicy retryPolicy;

    @BeforeEach
    void setUp() {
        properties.setBaseRetryDelay(Duration.ofMinutes(1));
        properties.setMaxRetryDelay(Duration.ofMinutes(3));
        properties.setMaxAttempts(3);
        retryPolicy = new WebhookRetryPolicy(properties);
    }

    @Test
    void appliesExponentialBackoffWithMaximumDelay() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0);
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{}");

        assertThat(retryPolicy.nextRetryAt(inbox, now)).isEqualTo(now.plusMinutes(1));

        inbox.fail("failure", now);
        inbox.retry();
        assertThat(retryPolicy.nextRetryAt(inbox, now)).isEqualTo(now.plusMinutes(2));

        inbox.fail("failure", now);
        inbox.retry();
        assertThat(retryPolicy.nextRetryAt(inbox, now)).isEqualTo(now.plusMinutes(3));
    }

    @Test
    void reportsExhaustionAtConfiguredMaximumAttempts() {
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{}");
        assertThat(retryPolicy.attemptsExhausted(inbox)).isFalse();

        inbox.retry();
        inbox.retry();

        assertThat(retryPolicy.attemptsExhausted(inbox)).isTrue();
    }
}
