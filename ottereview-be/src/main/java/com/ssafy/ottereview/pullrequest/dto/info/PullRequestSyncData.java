package com.ssafy.ottereview.pullrequest.dto.info;

import com.ssafy.ottereview.githubapp.dto.GithubPrResponse;
import com.ssafy.ottereview.webhook.dto.PullRequestEventDto;
import com.ssafy.ottereview.webhook.dto.PullRequestWebhookInfo;
import java.net.URL;
import java.time.LocalDateTime;

public record PullRequestSyncData(
        Long githubId,
        Integer githubPrNumber,
        String commitSha,
        String title,
        String body,
        String state,
        Boolean merged,
        String base,
        String head,
        Boolean mergeable,
        LocalDateTime githubCreatedAt,
        LocalDateTime githubUpdatedAt,
        Integer commitCnt,
        Integer changedFilesCnt,
        Integer commentCnt,
        Integer reviewCommentCnt,
        URL htmlUrl,
        URL patchUrl,
        URL issueUrl,
        URL diffUrl
) {

    public static PullRequestSyncData from(GithubPrResponse response) {
        return new PullRequestSyncData(
                response.getGithubId(),
                response.getGithubPrNumber(),
                response.getCommitSha(),
                response.getTitle(),
                response.getBody(),
                response.getState(),
                response.getMerged(),
                response.getBase(),
                response.getHead(),
                response.getMergeable(),
                response.getGithubCreatedAt(),
                response.getGithubUpdatedAt(),
                response.getCommitCnt(),
                response.getChangedFilesCnt(),
                response.getCommentCnt(),
                response.getReviewCommentCnt(),
                response.getHtmlUrl(),
                response.getPatchUrl(),
                response.getIssueUrl(),
                response.getDiffUrl()
        );
    }

    public static PullRequestSyncData from(PullRequestEventDto event) {
        PullRequestWebhookInfo pullRequest = event.getPullRequest();

        return new PullRequestSyncData(
                pullRequest.getId(),
                event.getNumber(),
                pullRequest.getHead().getSha(),
                pullRequest.getTitle(),
                pullRequest.getBody(),
                pullRequest.getState(),
                pullRequest.getMerged(),
                pullRequest.getBase().getRef(),
                pullRequest.getHead().getRef(),
                pullRequest.getMergeable(),
                pullRequest.getCreatedAt(),
                pullRequest.getUpdatedAt(),
                pullRequest.getCommits(),
                pullRequest.getChangedFiles(),
                pullRequest.getComments(),
                pullRequest.getReviewComments(),
                pullRequest.getHtmlUrl(),
                pullRequest.getPatchUrl(),
                pullRequest.getIssueUrl(),
                pullRequest.getDiffUrl()
        );
    }
}
