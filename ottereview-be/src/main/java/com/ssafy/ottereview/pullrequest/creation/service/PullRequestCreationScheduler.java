package com.ssafy.ottereview.pullrequest.creation.service;

import com.ssafy.ottereview.pullrequest.creation.config.PullRequestCreationProperties;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationOutboxStatus;
import com.ssafy.ottereview.pullrequest.creation.repository.PullRequestCreationOutboxRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PullRequestCreationScheduler {

    private final PullRequestCreationOutboxRepository outboxRepository;
    private final PullRequestCreationTransactionService transactionService;
    private final PullRequestCreationWorker worker;
    private final PullRequestCreationProperties properties;

    @Scheduled(
            initialDelayString = "${pull-request.creation.initial-delay-ms:5000}",
            fixedDelayString = "${pull-request.creation.fixed-delay-ms:5000}"
    )
    public void dispatch() {
        LocalDateTime now = LocalDateTime.now();
        try {
            recoverStale(now);
            List<Long> readyIds = outboxRepository.findReadyIds(
                    List.of(
                            PullRequestCreationOutboxStatus.PENDING,
                            PullRequestCreationOutboxStatus.RETRY_PENDING
                    ),
                    now,
                    PageRequest.of(0, properties.getBatchSize())
            );
            readyIds.forEach(id -> worker.process(id, now));
        } catch (RuntimeException exception) {
            log.error("PR 생성 Outbox 스케줄 실행에 실패했습니다.", exception);
        }
    }

    private void recoverStale(LocalDateTime now) {
        LocalDateTime threshold = now.minus(properties.getProcessingTimeout());
        List<Long> staleIds = outboxRepository.findStaleIds(
                PullRequestCreationOutboxStatus.PROCESSING,
                threshold,
                PageRequest.of(0, properties.getBatchSize())
        );
        staleIds.forEach(id -> transactionService.recoverStale(id, threshold, now));
    }
}
