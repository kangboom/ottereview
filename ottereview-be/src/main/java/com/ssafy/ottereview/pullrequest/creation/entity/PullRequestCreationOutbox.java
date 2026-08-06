package com.ssafy.ottereview.pullrequest.creation.entity;

import com.ssafy.ottereview.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "pull_request_creation_outbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pull_request_creation_outbox_task",
                columnNames = "task_id"
        ),
        indexes = {
                @Index(
                        name = "idx_pr_creation_outbox_status_next_attempt",
                        columnList = "status, next_attempt_at"
                ),
                @Index(
                        name = "idx_pr_creation_outbox_status_modified_at",
                        columnList = "status, modified_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PullRequestCreationOutbox extends BaseEntity {

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private PullRequestCreationTask task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PullRequestCreationOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static PullRequestCreationOutbox pending(
            PullRequestCreationTask task,
            LocalDateTime now
    ) {
        return new PullRequestCreationOutbox(
                null,
                task,
                PullRequestCreationOutboxStatus.PENDING,
                0,
                now,
                null,
                null
        );
    }

    public boolean canClaim(LocalDateTime now, int maxAttempts) {
        boolean readyStatus = status == PullRequestCreationOutboxStatus.PENDING
                || status == PullRequestCreationOutboxStatus.RETRY_PENDING;
        return readyStatus
                && attemptCount < maxAttempts
                && nextAttemptAt != null
                && !nextAttemptAt.isAfter(now);
    }

    public void startProcessing() {
        status = PullRequestCreationOutboxStatus.PROCESSING;
        attemptCount++;
        nextAttemptAt = null;
        lastError = null;
    }

    public void complete(LocalDateTime completedAt) {
        status = PullRequestCreationOutboxStatus.COMPLETED;
        this.completedAt = completedAt;
        nextAttemptAt = null;
        lastError = null;
    }

    public void retryLater(String errorMessage, LocalDateTime nextAttemptAt) {
        status = PullRequestCreationOutboxStatus.RETRY_PENDING;
        lastError = truncate(errorMessage);
        this.nextAttemptAt = nextAttemptAt;
    }

    public void exhaust(String errorMessage) {
        status = PullRequestCreationOutboxStatus.EXHAUSTED;
        lastError = truncate(errorMessage);
        nextAttemptAt = null;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
