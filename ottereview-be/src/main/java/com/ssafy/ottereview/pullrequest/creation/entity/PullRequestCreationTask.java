package com.ssafy.ottereview.pullrequest.creation.entity;

import com.ssafy.ottereview.common.entity.BaseEntity;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "pull_request_creation_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PullRequestCreationTask extends BaseEntity {

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repo repo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(name = "source_branch", nullable = false)
    private String source;

    @Column(name = "target_branch", nullable = false)
    private String target;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "preparation_payload", nullable = false, columnDefinition = "LONGTEXT")
    private String preparationPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PullRequestCreationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "github_id")
    private Long githubId;

    @Column(name = "github_pr_number")
    private Integer githubPrNumber;

    @Column(name = "pull_request_id")
    private Long pullRequestId;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public static PullRequestCreationTask request(
            Repo repo,
            User author,
            String source,
            String target,
            String title,
            String body,
            String preparationPayload
    ) {
        return new PullRequestCreationTask(
                null,
                repo,
                author,
                source,
                target,
                title,
                body,
                preparationPayload,
                PullRequestCreationStatus.REQUESTED,
                0,
                null,
                null,
                null,
                null,
                null
        );
    }

    public void startProcessing() {
        status = PullRequestCreationStatus.PROCESSING;
        attemptCount++;
        lastError = null;
    }

    public void markGithubCreated(Long githubId, Integer githubPrNumber) {
        this.githubId = githubId;
        this.githubPrNumber = githubPrNumber;
        status = PullRequestCreationStatus.GITHUB_CREATED;
    }

    public void complete(Long pullRequestId, LocalDateTime completedAt) {
        this.pullRequestId = pullRequestId;
        this.completedAt = completedAt;
        status = PullRequestCreationStatus.COMPLETED;
        lastError = null;
    }

    public void retryLater(String errorMessage) {
        status = PullRequestCreationStatus.RETRY_PENDING;
        lastError = truncate(errorMessage);
    }

    public void exhaust(String errorMessage) {
        status = PullRequestCreationStatus.EXHAUSTED;
        lastError = truncate(errorMessage);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
