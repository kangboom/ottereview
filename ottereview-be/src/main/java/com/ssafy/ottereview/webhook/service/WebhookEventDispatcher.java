package com.ssafy.ottereview.webhook.service;

import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.webhook.exception.WebhookErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebhookEventDispatcher {

    private final PushEventService pushEventService;
    private final InstallationEventService installationEventService;
    private final PullRequestEventService pullRequestEventService;
    private final ReviewEventService reviewEventService;
    private final ReviewCommentEventService reviewCommentEventService;
    private final BranchProtectionEventService branchProtectionEventService;
    private final RepoEventService repoEventService;

    public void dispatch(String eventType, String payload) {
        switch (eventType) {
            case "push" -> pushEventService.processPushEvent(payload);
            case "pull_request" -> pullRequestEventService.processPullRequestEvent(payload);
            case "pull_request_review" -> reviewEventService.processReviewEvent(payload);
            case "pull_request_review_comment" -> reviewCommentEventService.processReviewCommentEvent(payload);
            case "installation" -> installationEventService.processInstallationEvent(payload);
            case "installation_repositories" ->
                    installationEventService.processInstallationRepositoriesEvent(payload);
            case "create" -> installationEventService.processAddBranchesEvent(payload);
            case "delete" -> installationEventService.processDeleteBranchesEvent(payload);
            case "branch_protection_rule" -> branchProtectionEventService.processBranchProtection(payload);
            case "repository" -> repoEventService.processRepo(payload);
            default -> throw new BusinessException(WebhookErrorCode.WEBHOOK_UNSUPPORTED_EVENT);
        }
    }
}
