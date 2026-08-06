package com.ssafy.ottereview.webhook.service;

import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import com.ssafy.ottereview.webhook.repository.WebhookInboxRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebhookInboxProcessingService {

    private final WebhookInboxRepository webhookInboxRepository;
    private final WebhookEventDispatcher webhookEventDispatcher;
    @Transactional
    public void process(Long inboxId) {
        WebhookInbox inbox = webhookInboxRepository.findByIdForUpdate(inboxId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Webhook Inbox가 존재하지 않습니다.: " + inboxId));

        webhookEventDispatcher.dispatch(inbox.getEventType(), inbox.getPayload());
        inbox.succeed(LocalDateTime.now());
    }
}
