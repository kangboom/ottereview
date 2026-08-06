package com.ssafy.ottereview.pullrequest.creation.entity;

public enum PullRequestCreationStatus {
    REQUESTED,
    PROCESSING,
    GITHUB_CREATED,
    COMPLETED,
    RETRY_PENDING,
    EXHAUSTED
}
