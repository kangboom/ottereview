package com.ssafy.ottereview.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncData;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncResult;
import com.ssafy.ottereview.pullrequest.entity.PrState;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationStatus;
import com.ssafy.ottereview.pullrequest.creation.repository.PullRequestCreationTaskRepository;
import com.ssafy.ottereview.pullrequest.creation.service.PullRequestPreparationFinalizationService;
import com.ssafy.ottereview.pullrequest.exception.PullRequestErrorCode;
import com.ssafy.ottereview.pullrequest.repository.PullRequestRepository;
import com.ssafy.ottereview.pullrequest.service.PullRequestSyncService;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.repo.repository.RepoRepository;
import com.ssafy.ottereview.reviewer.dto.ReviewerResponse;
import com.ssafy.ottereview.reviewer.entity.ReviewStatus;
import com.ssafy.ottereview.reviewer.entity.Reviewer;
import com.ssafy.ottereview.reviewer.repository.ReviewerRepository;
import com.ssafy.ottereview.reviewer.service.ReviewerService;
import com.ssafy.ottereview.user.entity.User;
import com.ssafy.ottereview.user.exception.UserErrorCode;
import com.ssafy.ottereview.user.repository.UserRepository;
import com.ssafy.ottereview.webhook.controller.EventSendController;
import com.ssafy.ottereview.webhook.dto.PullRequestEventDto;
import com.ssafy.ottereview.webhook.dto.UserWebhookInfo;
import com.ssafy.ottereview.webhook.exception.WebhookErrorCode;
import jakarta.transaction.Transactional;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class PullRequestEventService {
    
    private final ObjectMapper objectMapper;
    private final PullRequestRepository pullRequestRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final ReviewerService reviewerService;
    private final ReviewerRepository reviewerRepository;
    private final PullRequestSyncService pullRequestSyncService;
    private final EventSendController eventSendController;
    private final PullRequestCreationTaskRepository pullRequestCreationTaskRepository;
    private final PullRequestPreparationFinalizationService preparationFinalizationService;
    
    public void processPullRequestEvent(String payload) {
        try {
            PullRequestEventDto event = objectMapper.readValue(payload, PullRequestEventDto.class);
            String formattedPayload = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(event);
            log.debug("DTO로 받은 PR 이벤트 정보: {}\n", formattedPayload);
            
            switch (event.getAction()) {
                case "opened":
                    // mergeable 값 null
                    log.debug("PR이 열린 경우 발생하는 callback");
                    handlePullRequestOpened(event);
                    break;
                
                case "closed":
                    log.debug("PR이 닫힌 경우 발생하는 callback");
                    handlePullRequestClosed(event);
                    break;
                
                case "review_requested":
                    log.debug("PR에 리뷰어가 요청된 경우 발생하는 callback(리뷰어 개수만큼 발생)");
                    break;
                
                case "labeled":
                    log.debug("PR에 label이 추가된 경우 발생하는 callback");
                    break;
                
                case "assigned":
                    log.debug("PR에 assigned가 할당된 경우 발생하는 callback");
                    break;
                
                case "review_request_removed":
                    log.debug("PR에 리뷰어 요청이 제거된 경우 발생하는 callback");
                    break;
                
                case "synchronize":
                    handlePullRequestSynchronize(event);
                    break;
                
                case "reopened":
                    handlePullRequestReopened(event);
                    break;
                
                case "edited":
                    handlePullRequestSynchronize(event);
                    break;
                
                default:
                    log.warn("Unhandled action: {}", event.getAction());
            }
        } catch (Exception e) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_UNSUPPORTED_ACTION);
        }
    }
    
    private void handlePullRequestReopened(PullRequestEventDto event) {
        Long githubId = event.getPullRequest()
                .getId();
        
        PullRequest pullRequest = pullRequestRepository.findByGithubId(githubId)
                .orElseThrow(() -> new BusinessException(PullRequestErrorCode.PR_NOT_FOUND));
        
        // PR 상태를 OPEN으로 변경
        pullRequest.updateState(PrState.OPEN);
    }
    
    // mergeable 값을 못가져옴..
    private void handlePullRequestSynchronize(PullRequestEventDto event) {
        log.debug("[웹훅 PR 동기화 로직 실행]");
        PullRequest pullRequest = synchronizePullRequest(event).pullRequest();
        
        List<ReviewerResponse> reviewerList = reviewerService.getReviewerByPullRequest(pullRequest.getId());
        // reviewer들의 state를 none으로 바꿔야한다.
        List<Reviewer> reviewers = reviewerList.stream()
                .map(r -> Reviewer.builder()
                        .id(r.getId())
                        .user(User.to(r.getUser()))
                        .status(ReviewStatus.NONE)
                        .pullRequest(PullRequest.to(r.getPullRequest()))
                        .build())
                .toList();
        
        // 만약 Synchronize 가 들어오면 모든 Reviewr들의 state를 None으로 초기화한다.
        reviewerRepository.saveAll(reviewers);
        
        log.debug("sync 온다~~~~~₩!!!!!!");
        eventSendController.push(event.getSender()
                .getId(), "synchronize", "synchronize");
    }
    
    private void handlePullRequestOpened(PullRequestEventDto event) {
        PullRequestSyncResult result = synchronizePullRequest(event);

        if (!result.created()) {
            log.debug("이미 존재하는 PR을 최신 웹훅 정보로 갱신했습니다. githubId: {}",
                    result.pullRequest().getGithubId());
            return;
        }

        PullRequest pullRequest = result.pullRequest();
        boolean workerOwnsFinalization = pullRequestCreationTaskRepository.existsActiveTask(
                pullRequest.getRepo().getId(),
                pullRequest.getHead(),
                pullRequest.getBase(),
                List.of(
                        PullRequestCreationStatus.REQUESTED,
                        PullRequestCreationStatus.PROCESSING,
                        PullRequestCreationStatus.GITHUB_CREATED,
                        PullRequestCreationStatus.RETRY_PENDING
                )
        );
        if (workerOwnsFinalization) {
            log.debug("PR 생성 Worker가 후속 데이터 저장을 담당합니다. githubId: {}",
                    pullRequest.getGithubId());
            return;
        }

        preparationFinalizationService.finalizeFromRedis(
                pullRequest,
                pullRequest.getRepo().getId(),
                pullRequest.getHead(),
                pullRequest.getBase()
        );
        preparationFinalizationService.cleanupRedisAfterCommit(
                pullRequest.getRepo().getId(),
                pullRequest.getHead(),
                pullRequest.getBase()
        );
    }
    
    private void handlePullRequestClosed(PullRequestEventDto event) {
        
        Long githubId = event.getPullRequest()
                .getId();
        
        PullRequest pullRequest = pullRequestRepository.findByGithubId(githubId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "깃허브 PR ID에 해당하는 PR이 존재하지 않습니다.: " + githubId));
        
        // PR 상태를 CLOSED로 변경
        pullRequest.updateState(PrState.CLOSED);
        
        log.debug("Pull Request with GitHub PR number {} has been closed.", githubId);
    }
    
    private PullRequestSyncResult synchronizePullRequest(PullRequestEventDto event) {
        Repo targetRepo = repoRepository.findByRepoId(event.getRepository().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "해당 repository ID에 해당하는 Repo가 존재하지 않습니다.: "
                                + event.getRepository().getId()));

        User author = userRepository.findByGithubId(event.getPullRequest().getUser()
                        .getId())
                .orElseGet(() -> registerUser(event.getPullRequest().getUser()));

        return pullRequestSyncService.synchronize(PullRequestSyncData.from(event), targetRepo, author);
    }
    
    private User registerUser(UserWebhookInfo userInfo) {
        try {
            User user = User.builder()
                    .githubId(userInfo.getId())
                    .githubUsername(userInfo.getLogin())
                    .githubEmail(userInfo.getEmail() != null ? userInfo.getEmail() : null)
                    .type(userInfo.getType())
                    .profileImageUrl(userInfo.getAvatarUrl() != null ? userInfo.getAvatarUrl()
                            .toString() : null)
                    .rewardPoints(0)
                    .userGrade("BASIC")
                    .build();
            
            return userRepository.save(user);
        } catch (Exception e) {
            throw new BusinessException(UserErrorCode.USER_REGISTRATION_FAILED);
        }
    }
}
