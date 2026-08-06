package com.ssafy.ottereview.webhook.service;

import com.ssafy.ottereview.webhook.dto.WebhookInboxStartResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookInboxService {

    private final WebhookInboxTransactionService transactionService;
    private final WebhookInboxProcessingService processingService;

    public boolean process(String deliveryId, String eventType, String payload) {
        WebhookInboxStartResult startResult = begin(deliveryId, eventType, payload);
        if (!startResult.shouldProcess()) {
            log.info("중복 웹훅 처리를 건너뜁니다. deliveryId: {}, eventType: {}", deliveryId, eventType);
            return false;
        }

        try {
            processingService.process(startResult.inboxId());
            return true;
        } catch (RuntimeException exception) {
            transactionService.markFailed(startResult.inboxId(), exception);
            throw exception;
        }
    }

    private WebhookInboxStartResult begin(String deliveryId, String eventType, String payload) {
        try {
            return transactionService.begin(deliveryId, eventType, payload);
        } catch (DataIntegrityViolationException duplicateException) {
            return transactionService.findDuplicate(deliveryId);
        }
    }
}
