package com.ssafy.ottereview.webhook.service;

import com.ssafy.ottereview.webhook.dto.WebhookInboxStartResult;
import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import com.ssafy.ottereview.webhook.repository.WebhookInboxRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebhookInboxTransactionService {

    private final WebhookInboxRepository webhookInboxRepository;
    private final WebhookRetryPolicy webhookRetryPolicy;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookInboxStartResult begin(String deliveryId, String eventType, String payload) {
        return webhookInboxRepository.findByDeliveryIdForUpdate(deliveryId)
                .map(this::beginExisting)
                .orElseGet(() -> beginNew(deliveryId, eventType, payload)); // 없으면 새로 생성
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public WebhookInboxStartResult findDuplicate(String deliveryId) {
        return webhookInboxRepository.findByDeliveryId(deliveryId)
                .map(inbox -> WebhookInboxStartResult.duplicate(inbox.getId()))
                .orElseThrow(() -> new IllegalStateException(
                        "중복 Delivery ID에 해당하는 Inbox가 존재하지 않습니다.: " + deliveryId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long inboxId, RuntimeException exception) {
        WebhookInbox inbox = getInbox(inboxId);
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        if (webhookRetryPolicy.attemptsExhausted(inbox)) {
            inbox.exhaust(message);
            return;
        }
        inbox.fail(message, webhookRetryPolicy.nextRetryAt(inbox, LocalDateTime.now()));
    }

    private WebhookInboxStartResult beginExisting(WebhookInbox inbox) {
        if (!inbox.isFailed()) {
            return WebhookInboxStartResult.duplicate(inbox.getId());
        }

        inbox.retry();
        return WebhookInboxStartResult.process(inbox.getId());
    }

    private WebhookInboxStartResult beginNew(String deliveryId, String eventType, String payload) {
        WebhookInbox saved = webhookInboxRepository.saveAndFlush(
                WebhookInbox.start(deliveryId, eventType, payload)
        );
        return WebhookInboxStartResult.process(saved.getId());
    }

    private WebhookInbox getInbox(Long inboxId) {
        return webhookInboxRepository.findByIdForUpdate(inboxId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Webhook Inbox가 존재하지 않습니다.: " + inboxId));
    }
}
