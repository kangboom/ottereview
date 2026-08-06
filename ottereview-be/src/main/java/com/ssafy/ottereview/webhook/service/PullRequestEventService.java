package com.ssafy.ottereview.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.description.dto.DescriptionBulkCreateRequest;
import com.ssafy.ottereview.description.dto.DescriptionResponse;
import com.ssafy.ottereview.description.exception.DescriptionErrorCde;
import com.ssafy.ottereview.description.service.DescriptionService;
import com.ssafy.ottereview.preparation.dto.DescriptionInfo;
import com.ssafy.ottereview.preparation.dto.PrUserInfo;
import com.ssafy.ottereview.preparation.dto.PreparationResult;
import com.ssafy.ottereview.preparation.repository.PreparationRedisRepository;
import com.ssafy.ottereview.priority.entity.Priority;
import com.ssafy.ottereview.priority.entity.PriorityFile;
import com.ssafy.ottereview.priority.repository.PriorityFileRepository;
import com.ssafy.ottereview.priority.repository.PriorityRepository;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncData;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncResult;
import com.ssafy.ottereview.pullrequest.entity.PrState;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
    private final PreparationRedisRepository preparationRedisRepository;
    private final PriorityRepository priorityRepository;
    private final PriorityFileRepository priorityFileRepository;
    private final DescriptionService descriptionService;
    private final PullRequestSyncService pullRequestSyncService;
    private final EventSendController eventSendController;
    
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
        saveRelatedDataFromRedis(pullRequest, pullRequest.getRepo().getId(),
                pullRequest.getHead(), pullRequest.getBase());
        cleanupRedisData(pullRequest.getRepo().getId(), pullRequest.getHead(), pullRequest.getBase());
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
    
    private void saveRelatedDataFromRedis(PullRequest pullRequest, Long repoId, String source, String target) {
        try {
            // Redis에서 prepareInfo 조회
            PreparationResult prepareInfo = preparationRedisRepository.getPrepareInfo(repoId, source, target);
            
            if (prepareInfo == null) {
                log.debug("Redis에 prepareInfo가 없습니다. - repoId: {}, source: {}, target: {}", repoId, source, target);
                return;
            }
            
            pullRequest.enrollAuthor(getUserFromUserInfo(prepareInfo.getAuthor()));
            
            if (prepareInfo.getSummary() != null) {
                pullRequest.enrollSummary(prepareInfo.getSummary());
            }
            
            // 1. 리뷰어 저장
            saveReviewers(pullRequest, prepareInfo);
            
            // 2. 우선순위 저장
            savePriorities(pullRequest, prepareInfo);
            
            // 3. 설명 저장
            saveDescriptions(pullRequest, prepareInfo, repoId, source, target);
            
        } catch (Exception e) {
            log.warn("Redis에서 prepareInfo 조회 및 관련 데이터 저장 실패 - repoId: {}, source: {}, target: {}",
                    repoId, source, target, e);
        }
    }
    
    private void saveReviewers(PullRequest pullRequest, PreparationResult prepareInfo) {
        if (prepareInfo.getReviewers() == null || prepareInfo.getReviewers()
                .isEmpty()) {
            log.debug("저장할 리뷰어가 없습니다.");
            return;
        }
        
        List<PrUserInfo> reviewers = prepareInfo.getReviewers();
        List<User> userList = reviewers.stream()
                .map(this::getUserFromUserInfo)
                .toList();
        
        List<Reviewer> reviewerList = userList.stream()
                .map(user -> Reviewer.builder()
                        .pullRequest(pullRequest)
                        .user(user)
                        .status(ReviewStatus.NONE)
                        .build())
                .toList();
        
        reviewerRepository.saveAll(reviewerList);
        log.debug("리뷰어 저장 완료, 리뷰어 수: {}", reviewerList.size());
    }
    
    private void savePriorities(PullRequest pullRequest, PreparationResult prepareInfo) {
        if (prepareInfo.getPriorities() == null || prepareInfo.getPriorities()
                .isEmpty()) {
            log.debug("저장할 우선순위가 없습니다.");
            return;
        }
        
        List<Priority> priorityList = prepareInfo.getPriorities()
                .stream()
                .map((priorityInfo) -> Priority.builder()
                        .pullRequest(pullRequest)
                        .title(priorityInfo.getTitle())
                        .level(priorityInfo.getLevel())
                        .content(priorityInfo.getContent())
                        .build())
                .toList();
        
        List<Priority> savedPriorities = priorityRepository.saveAll(priorityList);
        log.debug("우선 순위 저장 완료, 우선 순위 수: {}", savedPriorities.size());
        
        // 각 우선 순위와 관련 파일들을 매핑하여 저장
        for (int i = 0; i < prepareInfo.getPriorities()
                .size(); i++) {
            var priorityInfo = prepareInfo.getPriorities()
                    .get(i);
            Priority savedPriority = savedPriorities.get(i);
            
            if (priorityInfo.getRelatedFiles() != null && !priorityInfo.getRelatedFiles()
                    .isEmpty()) {
                List<PriorityFile> priorityFiles = priorityInfo.getRelatedFiles()
                        .stream()
                        .map(fileName -> PriorityFile.builder()
                                .fileName(fileName)
                                .priority(savedPriority)
                                .build())
                        .toList();
                
                priorityFileRepository.saveAll(priorityFiles);
                log.debug("우선 순위 ID: {} 관련 파일 저장 완료, 파일 수: {}",
                        savedPriority.getId(), priorityFiles.size());
            }
        }
    }
    
    private void saveDescriptions(PullRequest pullRequest, PreparationResult prepareInfo, Long repoId, String source, String target) {
        List<DescriptionInfo> descriptions = Optional.ofNullable(prepareInfo.getDescriptions())
                .orElse(List.of());
        
        if (descriptions.isEmpty()) {
            log.debug("저장할 설명이 없습니다.");
            return;
        }
        
        try {
            // Redis에서 파일 정보 조회
            String filesCacheKey = String.format("pr_files:%d:%s:%s", repoId, source, target);
            MultipartFile[] mediaFiles = preparationRedisRepository.getMediaFiles(filesCacheKey);
            
            // 기존 서비스 메서드 호출
            createDescriptionsWithService(prepareInfo, pullRequest, pullRequest.getAuthor()
                    .getId(), mediaFiles);
            
            log.debug("설명 저장 완료 - 기존 서비스 메서드 사용");
            
        } catch (Exception e) {
            log.warn("파일 정보 조회 실패, 파일 없이 설명만 저장 - repoId: {}, source: {}, target: {}",
                    repoId, source, target, e);
            
            // 파일 없이 설명만 저장
            createDescriptionsWithService(prepareInfo, pullRequest, pullRequest.getAuthor()
                    .getId(), null);
        }
    }
    
    /**
     * DescriptionService를 사용한 설명 일괄 생성 develop 브랜치의 PreparationResult 구조에 맞춰 수정
     */
    private void createDescriptionsWithService(PreparationResult prepareInfo, PullRequest pullRequest,
            Long userId, MultipartFile[] files) {
        // prepareInfo에서 descriptions가 없거나 비어있는 경우 처리
        List<DescriptionInfo> descriptions = Optional.ofNullable(prepareInfo.getDescriptions())
                .orElse(List.of());
        
        if (descriptions.isEmpty() && (files == null || files.length == 0)) {
            log.debug("생성할 설명이 없어 설명 저장을 건너뜁니다.");
            return;
        }
        
        // DescriptionInfo를 DescriptionBulkCreateRequest.DescriptionItemRequest로 변환
        List<DescriptionBulkCreateRequest.DescriptionItemRequest> descriptionItems = descriptions.stream()
                .map(info -> DescriptionBulkCreateRequest.DescriptionItemRequest.builder()
                        .path(info.getPath())
                        .body(info.getBody())
                        .position(info.getPosition())
                        .line(info.getLine())
                        .side(info.getSide())
                        .startLine(info.getStartLine())
                        .startSide(info.getStartSide())
                        .diffHunk(info.getDiffHunk())
                        .fileIndex(info.getFileIndex()) // 넘어온 정보에서 fileIndex 가져오기
                        .build())
                .toList();
        
        // 파일만 있고 description 정보가 없는 경우를 위한 처리
        if (descriptionItems.isEmpty() && files != null && files.length > 0) {
            throw new BusinessException(DescriptionErrorCde.DESCRIPTION_CREATE_FAILED, "설명 정보가 없고, 파일만 있는 경우는 허용되지 않습니다.");
        }
        
        if (!descriptionItems.isEmpty()) {
            DescriptionBulkCreateRequest bulkRequest = DescriptionBulkCreateRequest.builder()
                    .pullRequestId(pullRequest.getId())
                    .descriptions(descriptionItems)
                    .build();
            
            // DescriptionService를 통한 일괄 생성
            List<DescriptionResponse> createdDescriptions = descriptionService.createDescriptionsBulk(
                    bulkRequest, userId, files);
            
            log.debug("설명 일괄 생성 완료, 생성된 설명 수: {}", createdDescriptions.size());
        }
    }
    
    /**
     * PR 등록 완료 후 Redis 데이터 정리
     */
    private void cleanupRedisData(Long repoId, String source, String target) {
        try {
            // 1. prepareInfo 데이터 삭제
            preparationRedisRepository.deletePrepareInfo(repoId, source, target);
            
            // 2. 미디어 파일 데이터 삭제
            String filesCacheKey = String.format("pr_files:%d:%s:%s", repoId, source, target);
            preparationRedisRepository.deleteMediaFiles(filesCacheKey);
            
            log.debug("Redis 데이터 정리 완료 - repoId: {}, source: {}, target: {}", repoId, source, target);
            
        } catch (Exception e) {
            log.warn("Redis 데이터 정리 실패 - repoId: {}, source: {}, target: {}", repoId, source, target, e);
        }
    }
    
    private User getUserFromUserInfo(PrUserInfo userInfo) {
        return userRepository.findById(userInfo.getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userInfo.getId()));
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
