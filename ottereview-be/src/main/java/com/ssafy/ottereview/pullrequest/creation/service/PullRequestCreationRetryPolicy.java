package com.ssafy.ottereview.pullrequest.creation.service;

import com.ssafy.ottereview.pullrequest.creation.config.PullRequestCreationProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PullRequestCreationRetryPolicy {

    private final PullRequestCreationProperties properties;

    public LocalDateTime nextAttemptAt(int attemptCount, LocalDateTime now) {
        long multiplier = 1L << Math.max(0, attemptCount - 1);
        Duration delay = properties.getBaseRetryDelay().multipliedBy(multiplier);
        if (delay.compareTo(properties.getMaxRetryDelay()) > 0) {
            delay = properties.getMaxRetryDelay();
        }
        return now.plus(delay);
    }
}
