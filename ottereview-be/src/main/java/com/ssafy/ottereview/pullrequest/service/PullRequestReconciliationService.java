package com.ssafy.ottereview.pullrequest.service;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PullRequestReconciliationService {

    private final RepoRepository repoRepository;
    private final PullRequestRepository pullRequestRepository;
    private final GithubApiClient githubApiClient;
    private final GithubUserSyncService githubUserSyncService;
    private final PullRequestSyncService pullRequestSyncService;

    public void reconcileAll() {
        for (Repo repo : repoRepository.findAllBy()) {
            try {
                PullRequestReconciliationResult result = reconcile(repo);
                log.info(
                        "PR 정합성 검사 완료. repoId: {}, created: {}, synchronized: {}, rechecked: {}, failed: {}",
                        result.repoId(),
                        result.createdCount(),
                        result.synchronizedCount(),
                        result.recheckedCount(),
                        result.failedCount()
                );
            } catch (RuntimeException exception) {
                log.error("Repository PR 정합성 검사 실패. repoId: {}", repo.getId(), exception);
            }
        }
    }

    public PullRequestReconciliationResult reconcile(Repo repo) {
        Long installationId = requireInstallationId(repo);
        List<PullRequest> storedPullRequests = pullRequestRepository.findAllByRepo(repo);
        Map<Long, PullRequest> storedByGithubId = new HashMap<>();
        storedPullRequests.forEach(pr -> storedByGithubId.put(pr.getGithubId(), pr));

        List<GithubPrResponse> openPullRequests = githubApiClient.getPullRequests(
                installationId,
                repo.getFullName()
        );
        Set<Long> openGithubIds = new HashSet<>();
        int createdCount = 0;
        int synchronizedCount = 0;
        int failedCount = 0;

        for (GithubPrResponse githubPr : openPullRequests) {
            openGithubIds.add(githubPr.getGithubId());
            try {
                PullRequest existing = storedByGithubId.get(githubPr.getGithubId());
                User author = existing == null
                        ? githubUserSyncService.resolve(githubPr.getAuthor())
                        : existing.getAuthor();
                PullRequestSyncResult result = pullRequestSyncService.synchronize(
                        PullRequestSyncData.from(githubPr),
                        repo,
                        author
                );
                if (result.created()) {
                    createdCount++;
                } else {
                    synchronizedCount++;
                }
            } catch (RuntimeException exception) {
                failedCount++;
                log.warn(
                        "GitHub OPEN PR 동기화 실패. repoId: {}, githubPrNumber: {}",
                        repo.getId(),
                        githubPr.getGithubPrNumber(),
                        exception
                );
            }
        }

        int recheckedCount = 0;
        for (PullRequest stored : storedPullRequests) {
            if (stored.getState() != PrState.OPEN || openGithubIds.contains(stored.getGithubId())) {
                continue;
            }

            try {
                GithubPrResponse snapshot = githubApiClient.getPullRequestSnapshot(
                        installationId,
                        repo.getFullName(),
                        stored.getGithubPrNumber()
                );
                pullRequestSyncService.synchronize(
                        PullRequestSyncData.from(snapshot),
                        repo,
                        stored.getAuthor()
                );
                recheckedCount++;
            } catch (RuntimeException exception) {
                failedCount++;
                log.warn(
                        "GitHub PR 상세 재확인 실패. repoId: {}, githubPrNumber: {}",
                        repo.getId(),
                        stored.getGithubPrNumber(),
                        exception
                );
            }
        }

        return new PullRequestReconciliationResult(
                repo.getId(),
                createdCount,
                synchronizedCount,
                recheckedCount,
                failedCount
        );
    }

    private Long requireInstallationId(Repo repo) {
        if (repo.getAccount() == null || repo.getAccount().getInstallationId() == null) {
            throw new IllegalStateException(
                    "Repository에 GitHub App installationId가 없습니다. repoId: " + repo.getId());
        }
        return repo.getAccount().getInstallationId();
    }
}
