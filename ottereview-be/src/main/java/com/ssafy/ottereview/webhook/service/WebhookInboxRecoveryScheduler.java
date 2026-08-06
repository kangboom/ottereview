package com.ssafy.ottereview.webhook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookInboxRecoveryScheduler {

    private final WebhookInboxRecoveryService recoveryService;

    @Scheduled(fixedDelayString = "${webhook.inbox.recovery.fixed-delay-ms:30000}")
    public void recover() {
        try {
            recoveryService.recover();
        } catch (RuntimeException exception) {
            log.error("Webhook Inbox 복구 스케줄 실행에 실패했습니다.", exception);
        }
    }
}
