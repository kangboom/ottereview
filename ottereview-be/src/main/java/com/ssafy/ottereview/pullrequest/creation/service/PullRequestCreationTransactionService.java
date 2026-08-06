package com.ssafy.ottereview.pullrequest.creation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ottereview.preparation.dto.PreparationResult;
import com.ssafy.ottereview.pullrequest.creation.config.PullRequestCreationProperties;
import com.ssafy.ottereview.pullrequest.creation.dto.PullRequestCreationWorkItem;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationOutbox;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationOutboxStatus;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationTask;
import com.ssafy.ottereview.pullrequest.creation.repository.PullRequestCreationOutboxRepository;
import com.ssafy.ottereview.pullrequest.creation.repository.PullRequestCreationTaskRepository;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.pullrequest.repository.PullRequestRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PullRequestCreationTransactionService {

    private static final String PROCESSING_TIMEOUT_MESSAGE = "PR creation processing timeout";

    private final PullRequestCreationOutboxRepository outboxRepository;
    private final PullRequestCreationTaskRepository taskRepository;
    private final PullRequestCreationProperties properties;
    private final PullRequestCreationRetryPolicy retryPolicy;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestPreparationFinalizationService finalizationService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PullRequestCreationWorkItem claim(Long outboxId, LocalDateTime now) {
        PullRequestCreationOutbox outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElse(null);
        if (outbox == null || !outbox.canClaim(now, properties.getMaxAttempts())) {
            return null;
        }

        // 행 잠금 뒤 상태를 다시 확인하므로 여러 인스턴스가 같은 요청을 동시에 처리하지 않는다.
        outbox.startProcessing();
        PullRequestCreationTask task = outbox.getTask();
        task.startProcessing();

        return new PullRequestCreationWorkItem(
                task.getId(),
                outbox.getId(),
                task.getRepo().getId(),
                task.getAuthor().getId(),
                task.getRepo().getAccount().getInstallationId(),
                task.getRepo().getFullName(),
                task.getSource(),
                task.getTarget(),
                task.getTitle(),
                task.getBody(),
                task.getGithubId(),
                task.getGithubPrNumber()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGithubCreated(Long taskId, Long githubId, Integer githubPrNumber) {
        PullRequestCreationTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalStateException("PR 생성 작업을 찾을 수 없습니다: " + taskId));

        // 외부 생성 결과를 먼저 기록해 장애 후 재시도가 새 PR 대신 기존 PR에서 이어지게 한다.
        task.markGithubCreated(githubId, githubPrNumber);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long taskId, Long outboxId, Long pullRequestId, LocalDateTime now) {
        PullRequestCreationOutbox outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("PR 생성 Outbox를 찾을 수 없습니다: " + outboxId));
        PullRequestCreationTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalStateException("PR 생성 작업을 찾을 수 없습니다: " + taskId));

        PullRequest pullRequest = pullRequestRepository.findById(pullRequestId)
                .orElseThrow(() -> new IllegalStateException("저장된 PR을 찾을 수 없습니다: " + pullRequestId));

        // 부가 데이터 저장과 완료 표시는 같은 트랜잭션으로 묶어 재시도 시 중복 INSERT를 막는다.
        finalizationService.finalizeFromSnapshot(
                pullRequest,
                deserialize(task.getPreparationPayload()),
                task.getRepo().getId(),
                task.getSource(),
                task.getTarget()
        );
        task.complete(pullRequestId, now);
        outbox.complete(now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long outboxId, RuntimeException exception, LocalDateTime now) {
        PullRequestCreationOutbox outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElse(null);
        if (outbox == null) {
            return;
        }

        PullRequestCreationTask task = outbox.getTask();
        String message = exception.getMessage();
        if (outbox.getAttemptCount() >= properties.getMaxAttempts()) {
            outbox.exhaust(message);
            task.exhaust(message);
            return;
        }

        outbox.retryLater(message, retryPolicy.nextAttemptAt(outbox.getAttemptCount(), now));
        task.retryLater(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverStale(Long outboxId, LocalDateTime threshold, LocalDateTime now) {
        PullRequestCreationOutbox outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElse(null);
        if (outbox == null
                || outbox.getStatus() != PullRequestCreationOutboxStatus.PROCESSING
                || outbox.getModifiedAt() == null
                || !outbox.getModifiedAt().isBefore(threshold)) {
            return false;
        }

        PullRequestCreationTask task = outbox.getTask();
        if (outbox.getAttemptCount() >= properties.getMaxAttempts()) {
            outbox.exhaust(PROCESSING_TIMEOUT_MESSAGE);
            task.exhaust(PROCESSING_TIMEOUT_MESSAGE);
        } else {
            outbox.retryLater(
                    PROCESSING_TIMEOUT_MESSAGE,
                    retryPolicy.nextAttemptAt(outbox.getAttemptCount(), now)
            );
            task.retryLater(PROCESSING_TIMEOUT_MESSAGE);
        }
        return true;
    }

    private PreparationResult deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, PreparationResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("PR 준비 정보 스냅샷을 읽을 수 없습니다.", exception);
        }
    }
}
