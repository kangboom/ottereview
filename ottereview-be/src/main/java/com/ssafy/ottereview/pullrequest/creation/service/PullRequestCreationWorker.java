package com.ssafy.ottereview.pullrequest.creation.service;

import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.githubapp.client.GithubApiClient;
import com.ssafy.ottereview.githubapp.dto.GithubPrResponse;
import com.ssafy.ottereview.pullrequest.creation.dto.PullRequestCreationWorkItem;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncData;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncResult;
import com.ssafy.ottereview.pullrequest.service.PullRequestSyncService;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.repo.exception.RepoErrorCode;
import com.ssafy.ottereview.repo.repository.RepoRepository;
import com.ssafy.ottereview.user.entity.User;
import com.ssafy.ottereview.user.exception.UserErrorCode;
import com.ssafy.ottereview.user.repository.UserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PullRequestCreationWorker {

    private final PullRequestCreationTransactionService transactionService;
    private final GithubApiClient githubApiClient;
    private final PullRequestSyncService pullRequestSyncService;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final PullRequestPreparationFinalizationService finalizationService;

    public void process(Long outboxId, LocalDateTime now) {
        PullRequestCreationWorkItem workItem = transactionService.claim(outboxId, now);
        if (workItem == null) {
            return;
        }

        try {
            // GitHub 호출은 DB 트랜잭션 밖에서 실행해 느린 네트워크가 행 잠금을 오래 점유하지 않게 한다.
            GithubPrResponse response = resolvePullRequest(workItem);
            transactionService.recordGithubCreated(
                    workItem.taskId(),
                    response.getGithubId(),
                    response.getGithubPrNumber()
            );

            Repo repo = repoRepository.findById(workItem.repoId())
                    .orElseThrow(() -> new BusinessException(RepoErrorCode.REPO_NOT_FOUND));
            User author = userRepository.findById(workItem.authorId())
                    .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
            PullRequestSyncResult result = pullRequestSyncService.synchronize(
                    PullRequestSyncData.from(response),
                    repo,
                    author
            );

            transactionService.complete(
                    workItem.taskId(),
                    workItem.outboxId(),
                    result.pullRequest().getId(),
                    LocalDateTime.now()
            );
            finalizationService.cleanupRedis(
                    workItem.repoId(),
                    workItem.source(),
                    workItem.target()
            );
        } catch (RuntimeException exception) {
            transactionService.fail(workItem.outboxId(), exception, LocalDateTime.now());
            log.warn("PR 생성 작업 처리에 실패했습니다. outboxId: {}", outboxId, exception);
        }
    }

    private GithubPrResponse resolvePullRequest(PullRequestCreationWorkItem workItem) {
        if (workItem.githubPrNumber() != null) {
            return githubApiClient.getPullRequestSnapshot(
                    workItem.installationId(),
                    workItem.repositoryName(),
                    workItem.githubPrNumber()
            );
        }

        // API 성공 직후 서버가 종료된 경우에도 같은 head/base PR을 찾아 중복 생성을 피한다.
        return githubApiClient.findOpenPullRequest(
                        workItem.installationId(),
                        workItem.repositoryName(),
                        workItem.source(),
                        workItem.target()
                )
                .orElseGet(() -> GithubPrResponse.from(githubApiClient.createPullRequest(
                        workItem.installationId(),
                        workItem.repositoryName(),
                        workItem.title(),
                        workItem.body(),
                        workItem.source(),
                        workItem.target()
                )));
    }
}
