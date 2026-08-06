package com.ssafy.ottereview.pullrequest.creation.entity;

public enum PullRequestCreationOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_PENDING,
    COMPLETED,
    EXHAUSTED
}
