package com.ssafy.ottereview.pullrequest.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PullRequestReconciliationScheduler {

    private final PullRequestReconciliationService reconciliationService;

    @Scheduled(
            initialDelayString = "${pull-request.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${pull-request.reconciliation.fixed-delay-ms:900000}"
    )
    public void reconcile() {
        try {
            reconciliationService.reconcileAll();
        } catch (RuntimeException exception) {
            log.error("전체 PR 정합성 검사 스케줄 실행에 실패했습니다.", exception);
        }
    }
}
