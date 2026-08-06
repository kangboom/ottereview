package com.ssafy.ottereview.pullrequest.dto.info;

public record PullRequestReconciliationResult(
        Long repoId,
        int createdCount,
        int synchronizedCount,
        int recheckedCount,
        int failedCount
) {
}
