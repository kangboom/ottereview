package com.ssafy.ottereview.pullrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncData;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncResult;
import com.ssafy.ottereview.pullrequest.entity.PrState;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.pullrequest.repository.PullRequestRepository;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.user.entity.User;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PullRequestSyncServiceTest {

    @Mock
    private PullRequestRepository pullRequestRepository;

    @InjectMocks
    private PullRequestSyncService pullRequestSyncService;

    private final Repo repo = Repo.builder().id(10L).repoId(100L).fullName("team/repo").build();
    private final User author = User.builder().id(20L).githubId(200L).githubUsername("author").build();

    @Test
    void createsPullRequestWhenGithubIdDoesNotExist() {
        PullRequestSyncData data = syncData("open", false, "initial title", true);
        when(pullRequestRepository.findByGithubId(data.githubId())).thenReturn(Optional.empty());
        when(pullRequestRepository.save(any(PullRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PullRequestSyncResult result = pullRequestSyncService.synchronize(data, repo, author);

        assertThat(result.created()).isTrue();
        assertThat(result.pullRequest().getGithubId()).isEqualTo(data.githubId());
        assertThat(result.pullRequest().getRepo()).isSameAs(repo);
        assertThat(result.pullRequest().getAuthor()).isSameAs(author);
        assertThat(result.pullRequest().getState()).isEqualTo(PrState.OPEN);
        assertThat(result.pullRequest().getApproveCnt()).isZero();
        verify(pullRequestRepository).save(result.pullRequest());
    }

    @Test
    void updatesExistingPullRequestInsteadOfCreatingDuplicate() {
        PullRequest existing = PullRequest.builder()
                .id(1L)
                .githubId(1000L)
                .githubPrNumber(7)
                .repo(repo)
                .author(author)
                .title("old title")
                .state(PrState.OPEN)
                .merged(false)
                .base("develop")
                .head("feature/old")
                .mergeable(true)
                .approveCnt(0)
                .build();
        PullRequestSyncData data = syncData("CLOSED", true, "updated title", null);
        when(pullRequestRepository.findByGithubId(data.githubId())).thenReturn(Optional.of(existing));

        PullRequestSyncResult result = pullRequestSyncService.synchronize(data, repo, author);

        assertThat(result.created()).isFalse();
        assertThat(result.pullRequest()).isSameAs(existing);
        assertThat(existing.getTitle()).isEqualTo("updated title");
        assertThat(existing.getState()).isEqualTo(PrState.MERGED);
        assertThat(existing.getMerged()).isTrue();
        assertThat(existing.getHead()).isEqualTo("feature/new");
        assertThat(existing.getMergeable()).isTrue();
        verify(pullRequestRepository, never()).save(any(PullRequest.class));
    }

    private PullRequestSyncData syncData(String state, Boolean merged, String title, Boolean mergeable) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 10, 0);
        return new PullRequestSyncData(
                1000L,
                7,
                "new-sha",
                title,
                "body",
                state,
                merged,
                "develop",
                "feature/new",
                mergeable,
                now.minusDays(1),
                now,
                3,
                4,
                5,
                6,
                null,
                null,
                null,
                null
        );
    }
}
