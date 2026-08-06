package com.ssafy.ottereview.webhook.service;

import com.ssafy.ottereview.webhook.config.WebhookInboxRecoveryProperties;
import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebhookRetryPolicy {

    private final WebhookInboxRecoveryProperties properties;

    public boolean attemptsExhausted(WebhookInbox inbox) {
        return inbox.getAttemptCount() >= properties.getMaxAttempts();
    }

    public LocalDateTime nextRetryAt(WebhookInbox inbox, LocalDateTime now) {
        int exponent = Math.max(0, inbox.getAttemptCount() - 1);
        long multiplier = 1L << Math.min(exponent, 30);
        Duration delay = properties.getBaseRetryDelay().multipliedBy(multiplier);
        if (delay.compareTo(properties.getMaxRetryDelay()) > 0) {
            delay = properties.getMaxRetryDelay();
        }
        return now.plus(delay);
    }
}
