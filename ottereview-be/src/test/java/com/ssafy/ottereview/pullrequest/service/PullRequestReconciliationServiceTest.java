package com.ssafy.ottereview.pullrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.ottereview.account.entity.Account;
import com.ssafy.ottereview.githubapp.client.GithubApiClient;
import com.ssafy.ottereview.githubapp.dto.GithubPrResponse;
import com.ssafy.ottereview.githubapp.service.GithubUserSyncService;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestReconciliationResult;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncData;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncResult;
import com.ssafy.ottereview.pullrequest.entity.PrState;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.pullrequest.repository.PullRequestRepository;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.repo.repository.RepoRepository;
import com.ssafy.ottereview.user.entity.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kohsuke.github.GHUser;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PullRequestReconciliationServiceTest {

    @Mock
    private RepoRepository repoRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private GithubApiClient githubApiClient;

    @Mock
    private GithubUserSyncService githubUserSyncService;

    @Mock
    private PullRequestSyncService pullRequestSyncService;

    @Mock
    private GHUser githubAuthor;

    private PullRequestReconciliationService reconciliationService;
    private Repo repo;
    private User author;

    @BeforeEach
    void setUp() {
        reconciliationService = new PullRequestReconciliationService(
                repoRepository,
                pullRequestRepository,
                githubApiClient,
                githubUserSyncService,
                pullRequestSyncService
        );
        author = User.builder().id(20L).githubId(200L).githubUsername("author").build();
        repo = Repo.builder()
                .id(10L)
                .repoId(100L)
                .fullName("team/repo")
                .account(Account.builder().installationId(500L).build())
                .build();
    }

    @Test
    void createsOpenPullRequestMissingFromDatabase() {
        GithubPrResponse githubPr = githubPr(1000L, 7, "OPEN", false, githubAuthor);
        when(pullRequestRepository.findAllByRepo(repo)).thenReturn(List.of());
        when(githubApiClient.getPullRequests(500L, "team/repo")).thenReturn(List.of(githubPr));
        when(githubUserSyncService.resolve(githubAuthor)).thenReturn(author);
        when(pullRequestSyncService.synchronize(any(PullRequestSyncData.class), eq(repo), eq(author)))
                .thenReturn(new PullRequestSyncResult(PullRequest.builder().githubId(1000L).build(), true));

        PullRequestReconciliationResult result = reconciliationService.reconcile(repo);

        assertThat(result.createdCount()).isOne();
        assertThat(result.synchronizedCount()).isZero();
        assertThat(result.recheckedCount()).isZero();
        verify(githubUserSyncService).resolve(githubAuthor);
    }

    @Test
    void synchronizesExistingOpenPullRequestWithoutRegisteringAuthorAgain() {
        PullRequest stored = storedPullRequest(1000L, 7, PrState.OPEN);
        GithubPrResponse githubPr = githubPr(1000L, 7, "OPEN", false, null);
        when(pullRequestRepository.findAllByRepo(repo)).thenReturn(List.of(stored));
        when(githubApiClient.getPullRequests(500L, "team/repo")).thenReturn(List.of(githubPr));
        when(pullRequestSyncService.synchronize(any(PullRequestSyncData.class), eq(repo), eq(author)))
                .thenReturn(new PullRequestSyncResult(stored, false));

        PullRequestReconciliationResult result = reconciliationService.reconcile(repo);

        assertThat(result.createdCount()).isZero();
        assertThat(result.synchronizedCount()).isOne();
        verify(githubUserSyncService, never()).resolve(any());
    }

    @Test
    void rechecksDatabaseOpenPullRequestMissingFromGithubOpenList() {
        PullRequest stored = storedPullRequest(1000L, 7, PrState.OPEN);
        GithubPrResponse closedSnapshot = githubPr(1000L, 7, "CLOSED", true, null);
        when(pullRequestRepository.findAllByRepo(repo)).thenReturn(List.of(stored));
        when(githubApiClient.getPullRequests(500L, "team/repo")).thenReturn(List.of());
        when(githubApiClient.getPullRequestSnapshot(500L, "team/repo", 7))
                .thenReturn(closedSnapshot);
        when(pullRequestSyncService.synchronize(any(PullRequestSyncData.class), eq(repo), eq(author)))
                .thenReturn(new PullRequestSyncResult(stored, false));

        PullRequestReconciliationResult result = reconciliationService.reconcile(repo);

        assertThat(result.recheckedCount()).isOne();
        assertThat(result.failedCount()).isZero();
        verify(githubApiClient).getPullRequestSnapshot(500L, "team/repo", 7);
    }

    @Test
    void doesNotRecheckAlreadyClosedDatabasePullRequest() {
        PullRequest stored = storedPullRequest(1000L, 7, PrState.CLOSED);
        when(pullRequestRepository.findAllByRepo(repo)).thenReturn(List.of(stored));
        when(githubApiClient.getPullRequests(500L, "team/repo")).thenReturn(List.of());

        PullRequestReconciliationResult result = reconciliationService.reconcile(repo);

        assertThat(result.recheckedCount()).isZero();
        verify(githubApiClient, never()).getPullRequestSnapshot(any(), any(), any());
    }

    @Test
    void recordsDetailRecheckFailureWithoutFailingRepositoryBatch() {
        PullRequest stored = storedPullRequest(1000L, 7, PrState.OPEN);
        when(pullRequestRepository.findAllByRepo(repo)).thenReturn(List.of(stored));
        when(githubApiClient.getPullRequests(500L, "team/repo")).thenReturn(List.of());
        when(githubApiClient.getPullRequestSnapshot(500L, "team/repo", 7))
                .thenThrow(new RuntimeException("GitHub API error"));

        PullRequestReconciliationResult result = reconciliationService.reconcile(repo);

        assertThat(result.recheckedCount()).isZero();
        assertThat(result.failedCount()).isOne();
    }

    private PullRequest storedPullRequest(Long githubId, int githubPrNumber, PrState state) {
        return PullRequest.builder()
                .id(1L)
                .githubId(githubId)
                .githubPrNumber(githubPrNumber)
                .repo(repo)
                .author(author)
                .state(state)
                .title("title")
                .merged(state == PrState.MERGED)
                .base("develop")
                .head("feature")
                .mergeable(true)
                .approveCnt(0)
                .build();
    }

    private GithubPrResponse githubPr(
            Long githubId,
            int number,
            String state,
            boolean merged,
            GHUser githubUser
    ) {
        return GithubPrResponse.builder()
                .githubId(githubId)
                .githubPrNumber(number)
                .commitSha("sha")
                .title("title")
                .body("body")
                .state(state)
                .author(githubUser)
                .merged(merged)
                .base("develop")
                .head("feature")
                .mergeable(true)
                .build();
    }
}
