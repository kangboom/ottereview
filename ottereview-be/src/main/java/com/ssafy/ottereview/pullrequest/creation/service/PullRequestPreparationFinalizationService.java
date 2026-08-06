package com.ssafy.ottereview.pullrequest.creation.service;

import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.description.dto.DescriptionBulkCreateRequest;
import com.ssafy.ottereview.description.exception.DescriptionErrorCde;
import com.ssafy.ottereview.description.service.DescriptionService;
import com.ssafy.ottereview.preparation.dto.DescriptionInfo;
import com.ssafy.ottereview.preparation.dto.PreparationResult;
import com.ssafy.ottereview.preparation.repository.PreparationRedisRepository;
import com.ssafy.ottereview.priority.entity.Priority;
import com.ssafy.ottereview.priority.entity.PriorityFile;
import com.ssafy.ottereview.priority.repository.PriorityFileRepository;
import com.ssafy.ottereview.priority.repository.PriorityRepository;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.reviewer.entity.ReviewStatus;
import com.ssafy.ottereview.reviewer.entity.Reviewer;
import com.ssafy.ottereview.reviewer.repository.ReviewerRepository;
import com.ssafy.ottereview.user.entity.User;
import com.ssafy.ottereview.user.exception.UserErrorCode;
import com.ssafy.ottereview.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class PullRequestPreparationFinalizationService {

    private final PreparationRedisRepository preparationRedisRepository;
    private final ReviewerRepository reviewerRepository;
    private final PriorityRepository priorityRepository;
    private final PriorityFileRepository priorityFileRepository;
    private final DescriptionService descriptionService;
    private final UserRepository userRepository;

    @Transactional
    public void finalizeFromRedis(PullRequest pullRequest, Long repoId, String source, String target) {
        PreparationResult preparation = preparationRedisRepository.getPrepareInfo(repoId, source, target);
        if (preparation == null) {
            log.debug("Redis에 PR 준비 정보가 없습니다. repoId: {}, source: {}, target: {}",
                    repoId, source, target);
            return;
        }
        finalizeFromSnapshot(pullRequest, preparation, repoId, source, target);
    }

    public void finalizeFromSnapshot(
            PullRequest pullRequest,
            PreparationResult preparation,
            Long repoId,
            String source,
            String target
    ) {
        pullRequest.enrollAuthor(findUser(preparation.getAuthor().getId()));
        if (preparation.getSummary() != null) {
            pullRequest.enrollSummary(preparation.getSummary());
        }

        saveReviewers(pullRequest, preparation);
        savePriorities(pullRequest, preparation);
        saveDescriptions(pullRequest, preparation, repoId, source, target);
    }

    public void cleanupRedis(Long repoId, String source, String target) {
        try {
            preparationRedisRepository.deletePrepareInfo(repoId, source, target);
            preparationRedisRepository.deleteMediaFiles(mediaCacheKey(repoId, source, target));
        } catch (RuntimeException exception) {
            // DB 완료 상태는 되돌리지 않는다. Redis TTL이 남은 임시 데이터를 최종 정리한다.
            log.warn("PR 생성 후 Redis 임시 데이터 정리에 실패했습니다. repoId: {}", repoId, exception);
        }
    }

    public void cleanupRedisAfterCommit(Long repoId, String source, String target) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            cleanupRedis(repoId, source, target);
            return;
        }

        // RDB 롤백 시 Redis 원본까지 먼저 사라지는 일을 막기 위해 Commit 이후에만 삭제한다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupRedis(repoId, source, target);
            }
        });
    }

    private void saveReviewers(PullRequest pullRequest, PreparationResult preparation) {
        Optional.ofNullable(preparation.getReviewers()).orElse(List.of()).stream()
                .map(userInfo -> findUser(userInfo.getId()))
                .filter(user -> !reviewerRepository.existsByPullRequestAndUser(pullRequest, user))
                .map(user -> Reviewer.builder()
                        .pullRequest(pullRequest)
                        .user(user)
                        .status(ReviewStatus.NONE)
                        .build())
                .forEach(reviewerRepository::save);
    }

    private void savePriorities(PullRequest pullRequest, PreparationResult preparation) {
        var priorityInfos = Optional.ofNullable(preparation.getPriorities()).orElse(List.of());
        for (var priorityInfo : priorityInfos) {
            Priority priority = priorityRepository.save(Priority.builder()
                    .pullRequest(pullRequest)
                    .title(priorityInfo.getTitle())
                    .level(priorityInfo.getLevel())
                    .content(priorityInfo.getContent())
                    .build());

            Optional.ofNullable(priorityInfo.getRelatedFiles()).orElse(List.of()).stream()
                    .map(fileName -> PriorityFile.builder()
                            .fileName(fileName)
                            .priority(priority)
                            .build())
                    .forEach(priorityFileRepository::save);
        }
    }

    private void saveDescriptions(
            PullRequest pullRequest,
            PreparationResult preparation,
            Long repoId,
            String source,
            String target
    ) {
        List<DescriptionInfo> descriptions = Optional.ofNullable(preparation.getDescriptions())
                .orElse(List.of());
        if (descriptions.isEmpty()) {
            return;
        }

        MultipartFile[] mediaFiles = preparationRedisRepository.getMediaFiles(
                mediaCacheKey(repoId, source, target)
        );
        List<DescriptionBulkCreateRequest.DescriptionItemRequest> items = descriptions.stream()
                .map(info -> DescriptionBulkCreateRequest.DescriptionItemRequest.builder()
                        .path(info.getPath())
                        .body(info.getBody())
                        .position(info.getPosition())
                        .line(info.getLine())
                        .side(info.getSide())
                        .startLine(info.getStartLine())
                        .startSide(info.getStartSide())
                        .diffHunk(info.getDiffHunk())
                        .fileIndex(info.getFileIndex())
                        .build())
                .toList();

        if (items.isEmpty() && mediaFiles != null && mediaFiles.length > 0) {
            throw new BusinessException(
                    DescriptionErrorCde.DESCRIPTION_CREATE_FAILED,
                    "설명 정보 없이 파일만 저장할 수 없습니다."
            );
        }

        DescriptionBulkCreateRequest request = DescriptionBulkCreateRequest.builder()
                .pullRequestId(pullRequest.getId())
                .descriptions(items)
                .build();
        descriptionService.createDescriptionsBulk(
                request,
                pullRequest.getAuthor().getId(),
                mediaFiles
        );
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    private String mediaCacheKey(Long repoId, String source, String target) {
        return String.format("pr_files:%d:%s:%s", repoId, source, target);
    }
}
