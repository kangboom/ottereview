package com.ssafy.ottereview.pullrequest.creation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.ottereview.githubapp.client.GithubApiClient;
import com.ssafy.ottereview.githubapp.dto.GithubPrResponse;
import com.ssafy.ottereview.pullrequest.creation.dto.PullRequestCreationWorkItem;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncResult;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.pullrequest.service.PullRequestSyncService;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.repo.repository.RepoRepository;
import com.ssafy.ottereview.user.entity.User;
import com.ssafy.ottereview.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PullRequestCreationWorkerTest {

    @Mock
    private PullRequestCreationTransactionService transactionService;
    @Mock
    private GithubApiClient githubApiClient;
    @Mock
    private PullRequestSyncService pullRequestSyncService;
    @Mock
    private RepoRepository repoRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PullRequestPreparationFinalizationService finalizationService;
    @InjectMocks
    private PullRequestCreationWorker worker;

    private final LocalDateTime now = LocalDateTime.of(2026, 8, 6, 12, 0);

    @Test
    void reusesExistingGithubPullRequestBeforeCreatingAnotherOne() {
        PullRequestCreationWorkItem item = workItem();
        GithubPrResponse response = GithubPrResponse.builder()
                .githubId(100L)
                .githubPrNumber(7)
                .head("feature")
                .base("develop")
                .build();
        Repo repo = org.mockito.Mockito.mock(Repo.class);
        User author = org.mockito.Mockito.mock(User.class);
        PullRequest pullRequest = org.mockito.Mockito.mock(PullRequest.class);

        when(transactionService.claim(2L, now)).thenReturn(item);
        when(githubApiClient.findOpenPullRequest(30L, "owner/repo", "feature", "develop"))
                .thenReturn(Optional.of(response));
        when(repoRepository.findById(10L)).thenReturn(Optional.of(repo));
        when(userRepository.findById(20L)).thenReturn(Optional.of(author));
        when(pullRequest.getId()).thenReturn(40L);
        when(pullRequestSyncService.synchronize(any(), any(), any()))
                .thenReturn(new PullRequestSyncResult(pullRequest, true));

        worker.process(2L, now);

        verify(githubApiClient, never()).createPullRequest(any(), any(), any(), any(), any(), any());
        verify(transactionService).recordGithubCreated(1L, 100L, 7);
        verify(transactionService).complete(eq(1L), eq(2L), eq(40L), any());
        verify(finalizationService).cleanupRedis(10L, "feature", "develop");
    }

    @Test
    void recordsFailureForSchedulerRetry() {
        PullRequestCreationWorkItem item = workItem();
        RuntimeException failure = new RuntimeException("GitHub unavailable");
        when(transactionService.claim(2L, now)).thenReturn(item);
        when(githubApiClient.findOpenPullRequest(30L, "owner/repo", "feature", "develop"))
                .thenThrow(failure);

        worker.process(2L, now);

        verify(transactionService).fail(org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.same(failure), any());
        verify(transactionService, never()).complete(any(), any(), any(), any());
    }

    private PullRequestCreationWorkItem workItem() {
        return new PullRequestCreationWorkItem(
                1L,
                2L,
                10L,
                20L,
                30L,
                "owner/repo",
                "feature",
                "develop",
                "title",
                "body",
                null,
                null
        );
    }
}
