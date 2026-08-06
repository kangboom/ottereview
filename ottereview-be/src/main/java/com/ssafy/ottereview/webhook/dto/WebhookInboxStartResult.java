package com.ssafy.ottereview.webhook.dto;

public record WebhookInboxStartResult(Long inboxId, boolean shouldProcess) {

    public static WebhookInboxStartResult process(Long inboxId) {
        return new WebhookInboxStartResult(inboxId, true);
    }

    public static WebhookInboxStartResult duplicate(Long inboxId) {
        return new WebhookInboxStartResult(inboxId, false);
    }
}
