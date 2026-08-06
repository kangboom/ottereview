package com.ssafy.ottereview.pullrequest.creation.dto;

public record PullRequestCreationWorkItem(
        Long taskId,
        Long outboxId,
        Long repoId,
        Long authorId,
        Long installationId,
        String repositoryName,
        String source,
        String target,
        String title,
        String body,
        Long githubId,
        Integer githubPrNumber
) {
}
