package com.ssafy.ottereview.pullrequest.creation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.user.entity.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PullRequestCreationOutboxTest {

    @Test
    void pendingRequestCanBeClaimedAndCompleted() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0);
        PullRequestCreationTask task = task();
        PullRequestCreationOutbox outbox = PullRequestCreationOutbox.pending(task, now);

        assertThat(outbox.canClaim(now, 3)).isTrue();

        outbox.startProcessing();
        task.startProcessing();
        task.markGithubCreated(100L, 7);
        task.complete(20L, now.plusSeconds(1));
        outbox.complete(now.plusSeconds(1));

        assertThat(outbox.getStatus()).isEqualTo(PullRequestCreationOutboxStatus.COMPLETED);
        assertThat(outbox.getAttemptCount()).isOne();
        assertThat(task.getStatus()).isEqualTo(PullRequestCreationStatus.COMPLETED);
        assertThat(task.getGithubId()).isEqualTo(100L);
        assertThat(task.getPullRequestId()).isEqualTo(20L);
    }

    @Test
    void retryWaitsUntilBackoffTime() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0);
        PullRequestCreationOutbox outbox = PullRequestCreationOutbox.pending(task(), now);
        outbox.startProcessing();
        outbox.retryLater("GitHub unavailable", now.plusMinutes(1));

        assertThat(outbox.canClaim(now.plusSeconds(59), 3)).isFalse();
        assertThat(outbox.canClaim(now.plusMinutes(1), 3)).isTrue();
    }

    private PullRequestCreationTask task() {
        return PullRequestCreationTask.request(
                mock(Repo.class),
                mock(User.class),
                "feature",
                "develop",
                "title",
                "body",
                "{}"
        );
    }
}
