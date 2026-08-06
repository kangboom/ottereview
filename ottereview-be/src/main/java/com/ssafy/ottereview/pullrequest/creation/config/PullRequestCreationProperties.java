package com.ssafy.ottereview.pullrequest.creation.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pull-request.creation")
@Getter
@Setter
public class PullRequestCreationProperties {

    private Duration processingTimeout = Duration.ofMinutes(10);
    private Duration baseRetryDelay = Duration.ofMinutes(1);
    private Duration maxRetryDelay = Duration.ofMinutes(15);
    private int batchSize = 20;
    private int maxAttempts = 3;
}
