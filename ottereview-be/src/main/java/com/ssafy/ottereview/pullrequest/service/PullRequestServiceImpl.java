package com.ssafy.ottereview.pullrequest.service;

import com.ssafy.ottereview.account.service.UserAccountService;
import com.ssafy.ottereview.common.config.utils.CursorUtils;
import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.description.repository.DescriptionRepository;
import com.ssafy.ottereview.githubapp.client.GithubApiClient;
import com.ssafy.ottereview.githubapp.dto.GithubPrResponse;
import com.ssafy.ottereview.preparation.dto.PrUserInfo;
import com.ssafy.ottereview.preparation.dto.PreparationResult;
import com.ssafy.ottereview.preparation.repository.PreparationRedisRepository;
import com.ssafy.ottereview.priority.entity.PriorityFile;
import com.ssafy.ottereview.priority.repository.PriorityFileRepository;
import com.ssafy.ottereview.priority.repository.PriorityRepository;
import com.ssafy.ottereview.pullrequest.dto.info.MergeStatusResult;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestDescriptionInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestPriorityInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestReviewCommentInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestReviewInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestReviewerInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncData;
import com.ssafy.ottereview.pullrequest.dto.request.PullRequestCreateRequest;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestDetailResponse;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestResponse;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestValidationResponse;
import com.ssafy.ottereview.pullrequest.entity.PrState;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.pullrequest.exception.PullRequestErrorCode;
import com.ssafy.ottereview.pullrequest.repository.PullRequestRepository;
import com.ssafy.ottereview.pullrequest.util.PullRequestMapper;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.repo.exception.RepoErrorCode;
import com.ssafy.ottereview.repo.repository.RepoRepository;
import com.ssafy.ottereview.review.repository.ReviewRepository;
import com.ssafy.ottereview.reviewcomment.repository.ReviewCommentRepository;
import com.ssafy.ottereview.reviewer.entity.ReviewStatus;
import com.ssafy.ottereview.reviewer.entity.Reviewer;
import com.ssafy.ottereview.reviewer.repository.ReviewerRepository;
import com.ssafy.ottereview.s3.service.S3Service;
import com.ssafy.ottereview.user.entity.CustomUserDetail;
import com.ssafy.ottereview.user.entity.User;
import com.ssafy.ottereview.user.exception.UserErrorCode;
import com.ssafy.ottereview.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional
public class PullRequestServiceImpl implements PullRequestService {

    private final GithubApiClient githubApiClient;
    private final PullRequestRepository pullRequestRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final ReviewerRepository reviewerRepository;
    private final UserAccountService userAccountService;
    private final PriorityRepository priorityRepository;
    private final DescriptionRepository descriptionRepository;
    private final PullRequestMapper pullRequestMapper;
    private final PullRequestSyncService pullRequestSyncService;
    private final PreparationRedisRepository preparationRedisRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final S3Service s3Service;
    private final PriorityFileRepository priorityFileRepository;

    @Override
    public List<PullRequestResponse> getPullRequests(CustomUserDetail customUserDetail, Long repoId, Integer limit, String cursor) {
        // 1. 사용자 권한 검증 및 레포지토리 조회
        Repo targetRepo = userAccountService.validateUserPermission(customUserDetail.getUser()
                .getId(), repoId);

        Instant cUpdated = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            var c = CursorUtils.decode(cursor);
            cUpdated = c.updatedAt();
            cId = c.id();
        }

        int pageSize = (limit != null && limit > 0) ? Math.min(limit, 100) : 20;

        // 2. 해당 레포지토리의 Pull Request 목록 조회
//        Pageable pageable = PageRequest.of(page,size, Sort.by(Sort.Direction.DESC,"githubCreatedAt"));
        List<PullRequest> pullRequests = pullRequestRepository.findSlice(targetRepo.getRepoId(), cUpdated, cId, PageRequest.of(0, pageSize));

        // 3. Pull Request 목록을 DTO로 변환하여 반환
        return pullRequests.stream()
                .map(pullRequest -> {
                    MergeStatusResult mergeStatus = checkMergeStatus(pullRequest);
                    return PullRequestResponse.fromEntityAndMergeStatus(pullRequest, mergeStatus);
                })
                .toList();
    }

    private MergeStatusResult checkMergeStatus(PullRequest pullRequest) {
        // Reviewer 모두 APPROVED인지 확인
        List<Reviewer> reviewers = reviewerRepository.findByPullRequest(pullRequest);
        
        int approveCount = (int) reviewers.stream()
                .mapToLong(r -> r.getStatus() == ReviewStatus.APPROVED ? 1 : 0)
                .sum();
        
        boolean allApproved = reviewers.stream()
                .allMatch(r -> r.getStatus() == ReviewStatus.APPROVED);
        
        return new MergeStatusResult(allApproved, approveCount);
    }

    @Override
    public List<PullRequestResponse> getMyPullRequests(CustomUserDetail customUserDetail) {
        User loginUser = userRepository.findById(customUserDetail.getUser()
                        .getId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        List<PullRequest> pullRequests = pullRequestRepository.findAllByAuthor(loginUser)
                .stream()
                .filter(pr -> pr.getState()
                        .equals(PrState.OPEN))
                .toList();

        return pullRequests.stream()
                .map(pullRequestMapper::PullRequestToResponse)
                .toList();
    }

    @Override
    public PullRequestDetailResponse getPullRequest(CustomUserDetail customUserDetail,
            Long repoId, Long prId) {

        Repo repo = userAccountService.validateUserPermission(customUserDetail.getUser()
                .getId(), repoId);

        PullRequest pullRequest = pullRequestRepository.findById(prId)
                .orElseThrow(() -> new BusinessException(PullRequestErrorCode.PR_NOT_FOUND));

        PullRequestDetailResponse pullRequestDetailResponse = githubApiClient.getPullRequestDetail(prId, repo.getFullName());

        // 리뷰어 추가
        List<PullRequestReviewerInfo> reviewers = reviewerRepository.findAllByPullRequest(pullRequest)
                .stream()
                .map(PullRequestReviewerInfo::fromEntity)
                .toList();

        // Description 추가
        List<PullRequestDescriptionInfo> descriptions = descriptionRepository.findAllByPullRequest(pullRequest)
                .stream()
                .map(description -> {
                    String voiceFileUrl = null;
                    if (description.getRecordKey() != null && !description.getRecordKey().trim().isEmpty()) {
                        try {
                            voiceFileUrl = s3Service.generatePresignedUrl(description.getRecordKey(), 60);
                        } catch (Exception e) {
                            log.warn("Description 음성 파일 URL 생성 실패 - descriptionId: {}, recordKey: {}", 
                                description.getId(), description.getRecordKey());
                        }
                    }
                    
                    return PullRequestDescriptionInfo.builder()
                            .id(description.getId())
                            .path(description.getPath())
                            .body(description.getBody())
                            .recordKey(description.getRecordKey())
                            .position(description.getPosition())
                            .startLine(description.getStartLine())
                            .startSide(description.getStartSide())
                            .line(description.getLine())
                            .side(description.getSide())
                            .diffHunk(description.getDiffHunk())
                            .voiceFileUrl(voiceFileUrl)
                            .build();
                })
                .toList();

        // Review 추가
        List<PullRequestReviewInfo> reviews = reviewRepository.findAllByPullRequest(pullRequest)
                .stream()
                .map(review -> {
                    List<PullRequestReviewCommentInfo> reviewComments = reviewCommentRepository.findAllByReview(review)
                            .stream()
                            .map(reviewComment -> {
                                String voiceFileUrl = null;
                                if (reviewComment.getRecordKey() != null) {
                                    try {
                                        voiceFileUrl = s3Service.generatePresignedUrl(reviewComment.getRecordKey(), 60);
                                    } catch (Exception e) {
                                        // URL 생성 실패 시 null 처리
                                        log.debug("음성 파일 URL 생성 실패: null 반환 {}", e.getMessage());
                                    }
                                }
                                return PullRequestReviewCommentInfo.fromEntity(reviewComment, voiceFileUrl);
                            })
                            .toList();
                    return PullRequestReviewInfo.fromEntityAndReviewComment(review, reviewComments);
                })
                .toList();

        // 우선순위 추가
        List<PullRequestPriorityInfo> priorities = priorityRepository.findAllByPullRequest(pullRequest)
                .stream()
                .map(priority -> {
                    List<PriorityFile> allByPriority = priorityFileRepository.findAllByPriority(priority);
                    List<String> relatedFiles = allByPriority.stream()
                            .map(PriorityFile::getFileName)
                            .toList();
                    return PullRequestPriorityInfo.fromEntityAndFiles(priority, relatedFiles);
                })
                .toList();

        pullRequestDetailResponse.enrollDescription(descriptions);
        pullRequestDetailResponse.enrollReviewers(reviewers);
        pullRequestDetailResponse.enrollReview(reviews);
        pullRequestDetailResponse.enrollPriorities(priorities);

        return pullRequestDetailResponse;
    }

    @Override
    public PullRequestValidationResponse getPullRequestByBranch(CustomUserDetail userDetail, Long repoId, String source, String target) {

        Repo repo = userAccountService.validateUserPermission(userDetail.getUser()
                .getId(), repoId);

        Optional<PullRequest> existPullRequest = pullRequestRepository.findByRepoAndBaseAndHeadAndState(repo, target, source, PrState.OPEN);

        if (existPullRequest.isPresent()) {
            return new PullRequestValidationResponse(true, existPullRequest.get()
                    .getId());
        }

        return new PullRequestValidationResponse(false, null);
    }

    @Override
    public void createPullRequestWithMediaFiles(CustomUserDetail customUserDetail, Long repoId,
            PullRequestCreateRequest request, MultipartFile[] mediaFiles) {

        GithubPrResponse prResponse = null;

        try {
            Repo repo = userAccountService.validateUserPermission(customUserDetail.getUser()
                    .getId(), repoId);

            // redis 캐시에서 PR 생성 가능 여부 확인
            PreparationResult prepareInfo = preparationRedisRepository.getPrepareInfo(repoId, request.getSource(), request.getTarget());

            if (prepareInfo == null || !prepareInfo.getIsPossible()) {
                throw new BusinessException(PullRequestErrorCode.PR_VALIDATION_FAILED);
            }

            // PR 생성 검증
            validatePullRequestCreation(prepareInfo, repo);

            // GitHub에 PR 생성 (DB 저장은 Webhook에서 처리)
            prResponse = GithubPrResponse.from(githubApiClient.createPullRequest(repo.getAccount()
                    .getInstallationId(), repo.getFullName(), prepareInfo.getTitle(), prepareInfo.getBody(), request.getSource(), request.getTarget())
            );

            // 파일 정보를 Redis에 저장 (Webhook에서 사용하기 위해)
            if (mediaFiles != null && mediaFiles.length > 0) {
                String filesCacheKey = String.format("pr_files:%d:%s:%s", repoId, request.getSource(), request.getTarget());
                preparationRedisRepository.saveMediaFiles(filesCacheKey, mediaFiles);
                log.debug("미디어 파일 Redis 저장 완료 - Key: {}, 파일 수: {}", filesCacheKey, mediaFiles.length);
            }
            
        } catch (Exception e) {
            log.error("Pull Request 생성 중 오류 발생: {}", e.getMessage(), e);
            // 오류 발생 시 Github PR 삭제
            try {
                if (prResponse != null) {
                    Repo repo = repoRepository.findById(repoId)
                            .orElseThrow(() -> new BusinessException(RepoErrorCode.REPO_NOT_FOUND));

                    githubApiClient.closePullRequest(repo.getAccount()
                            .getInstallationId(), repo.getFullName(), prResponse.getGithubPrNumber());
                }
            } catch (Exception deleteEx) {
                log.error("Pull Request 삭제 중 오류 발생: {}", deleteEx.getMessage(), deleteEx);
                throw new BusinessException(PullRequestErrorCode.PR_CREATE_FAILED, "PR 생성 중 오류가 발생하였고, PR 삭제에도 실패하였습니다.");
            }
        }
    }

    @Override
    public void createPullRequestFromGithub(List<GHRepository> githubRepositories) {

        try {
            //6. Repo 별 PR 생성
            for (GHRepository repo : githubRepositories) {
                log.debug("Repo ID: {}, Full Name: {}, Private: {}",
                        repo.getId(), repo.getFullName(), repo.isPrivate());

                List<GithubPrResponse> githubPrResponses = repo.queryPullRequests()
                        .state(GHIssueState.OPEN)
                        .list()
                        .toList()
                        .stream()
                        .map(GithubPrResponse::from)
                        .toList();

                // 1. repositoryId로 Repo 엔티티를 조회한다.
                Repo targetRepo = repoRepository.findByRepoId(repo.getId())
                        .orElseThrow(() -> new BusinessException(RepoErrorCode.REPO_NOT_FOUND));

                for (GithubPrResponse githubPr : githubPrResponses) {

                    User author = userRepository.findByGithubId(githubPr.getAuthor()
                                    .getId())
                            .orElseGet(() -> registerUser(githubPr.getAuthor()));

                    PullRequest pullRequest = pullRequestSyncService
                            .synchronize(PullRequestSyncData.from(githubPr), targetRepo, author)
                            .pullRequest();

                    List<GHUser> users = githubPr.getRequestedReviewers();
                    for (GHUser user : users) {
                        User reviewer = userRepository.findByGithubId(user
                                        .getId())
                                .orElseGet(() -> registerUser(user));

                        if (!reviewerRepository.existsByPullRequestAndUser(pullRequest, reviewer)) {
                            reviewerRepository.save(Reviewer.builder()
                                    .pullRequest(pullRequest)
                                    .user(reviewer)
                                    .status(ReviewStatus.NONE)
                                    .build());
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new BusinessException(PullRequestErrorCode.PR_CREATE_FAILED);
        }

    }

    private User registerUser(GHUser ghUser) {
        try {
            User user = User.builder()
                    .githubId(ghUser.getId())
                    .githubUsername(ghUser.getLogin())
                    .githubEmail(ghUser.getEmail() != null ? ghUser.getEmail() : null)
                    .type(ghUser.getType())
                    .profileImageUrl(ghUser.getAvatarUrl() != null ? ghUser.getAvatarUrl()
                            .toString() : null)
                    .rewardPoints(0)
                    .userGrade("BASIC")
                    .build();

            return userRepository.save(user);
        } catch (Exception e) {
            throw new BusinessException(UserErrorCode.USER_REGISTRATION_FAILED);
        }
    }

    private User getUserFromUserInfo(PrUserInfo prUserInfo) {
        return userRepository.findById(prUserInfo.getId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    /**
     * pullRequest 생성 시 필요한 정보가 모두 있는지 검증하는 메서드
     */
    private void validatePullRequestCreation(PreparationResult preparationResult, Repo repo) {
        if (preparationResult.getSource() == null || preparationResult.getTarget() == null || preparationResult.getTitle() == null || repo.getFullName() == null) {
            throw new BusinessException(PullRequestErrorCode.PR_VALIDATION_FAILED);
        }
    }

    @Override
    @Transactional
    public void closePullRequest(CustomUserDetail userDetail, Long repoId, Long pullRequestId) {
        Repo repo = userAccountService.validateUserPermission(userDetail.getUser()
                .getId(), repoId);

        PullRequest pullRequest = pullRequestRepository.findById(pullRequestId)
                .orElseThrow(() -> new BusinessException(PullRequestErrorCode.PR_NOT_FOUND));

        // 1. GitHub에서도 PR을 닫음
        Long installationId = repo.getAccount()
                .getInstallationId();
        String repositoryName = repo.getFullName();
        Integer githubPrNumber = pullRequest.getGithubPrNumber();

        githubApiClient.closePullRequest(installationId, repositoryName, githubPrNumber);

        // 2. DB에서 PR 상태를 CLOSED로 변경
        pullRequest.updateState(PrState.CLOSED);
    }

    @Override
    @Transactional
    public void reopenPullRequest(CustomUserDetail userDetail, Long repoId, Long pullRequestId) {
        Repo repo = userAccountService.validateUserPermission(userDetail.getUser()
                .getId(), repoId);

        PullRequest pullRequest = pullRequestRepository.findById(pullRequestId)
                .orElseThrow(() -> new BusinessException(PullRequestErrorCode.PR_NOT_FOUND));

        if (pullRequestRepository.existsByRepoAndBaseAndHeadAndState(repo, pullRequest.getBase(), pullRequest.getHead(), PrState.OPEN)) {
            throw new BusinessException(PullRequestErrorCode.PR_ALREADY_OPEN);
        }

        // 1. GitHub에서도 PR을 다시 염
        Long installationId = repo.getAccount()
                .getInstallationId();
        String repositoryName = repo.getFullName();
        Integer githubPrNumber = pullRequest.getGithubPrNumber();

        githubApiClient.reopenPullRequest(installationId, repositoryName, githubPrNumber);

        // 2. DB에서 PR 상태를 OPEN 변경
        pullRequest.updateState(PrState.OPEN);
    }
}
