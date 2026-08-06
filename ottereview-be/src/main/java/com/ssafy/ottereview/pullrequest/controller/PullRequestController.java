package com.ssafy.ottereview.pullrequest.controller;

import com.ssafy.ottereview.common.annotation.MvcController;
import com.ssafy.ottereview.pullrequest.dto.request.PullRequestCreateRequest;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestDetailResponse;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestResponse;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestValidationResponse;
import com.ssafy.ottereview.pullrequest.service.PullRequestService;
import com.ssafy.ottereview.user.entity.CustomUserDetail;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/repositories/{repo-id}/pull-requests")
@RequiredArgsConstructor
@MvcController
public class PullRequestController {

    private final PullRequestService pullRequestService;

    @GetMapping("/search")
    @Operation(
            summary = "브랜치 정보를 이용한 Open 상태 Pull Request 단일 조회",
            description = "특정 레포지토리의 소스 브랜치와 타겟 브랜치에 대한 Pull-Request를 조회합니다."
    )
    public ResponseEntity<PullRequestValidationResponse> getPullRequestByBranch(
            @AuthenticationPrincipal CustomUserDetail userDetail,
            @PathVariable("repo-id") Long repoId,
            @RequestParam String source,
            @RequestParam String target
    ) {
        return ResponseEntity.ok(pullRequestService.getPullRequestByBranch(userDetail, repoId, source, target));
    }

    @GetMapping("/{pr-id}")
    @Operation(
            summary = "Pull Request 상세 조회",
            description = "특정 Pull Request의 상세 정보를 조회합니다."
    )
    public ResponseEntity<PullRequestDetailResponse> getPullRequest(@AuthenticationPrincipal CustomUserDetail userDetail, @PathVariable("repo-id") Long repoId,
            @PathVariable("pr-id") Long pullRequestId) {
        return ResponseEntity.ok(pullRequestService.getPullRequest(userDetail, repoId, pullRequestId));
    }

    @GetMapping()
    @Operation(
            summary = "레포지토리 Pull Request 목록 조회",
            description = "특정 레포지토리에 대한 Pull Request 목록을 조회합니다."
    )
    public ResponseEntity<List<PullRequestResponse>> getPullRequests(@AuthenticationPrincipal CustomUserDetail userDetail,
                                                                     @PathVariable("repo-id") Long repoId,
                                                                     @RequestParam Integer limit,
                                                                     @RequestParam Integer size,
                                                                     @RequestParam String cursor) {
        Integer effectiveLimit = (limit != null) ? limit : size;
        return ResponseEntity.ok(pullRequestService.getPullRequests(userDetail, repoId, effectiveLimit, cursor));
    }

    @PostMapping(value = "", consumes = "multipart/form-data")
    @Operation(
            summary = "미디어 파일과 함께 Pull Request 생성",
            description = "음성/이미지 등의 미디어 파일과 함께 새로운 Pull Request를 생성합니다."
    )
    public ResponseEntity<Void> createPullRequestWithMediaFiles(
            @AuthenticationPrincipal CustomUserDetail userDetail,
            @PathVariable("repo-id") Long repoId,
            @RequestPart("pullRequest") PullRequestCreateRequest request,
            @RequestPart(value = "mediaFiles", required = false) MultipartFile[] mediaFiles) {

        pullRequestService.createPullRequestWithMediaFiles(userDetail, repoId, request, mediaFiles);
        return ResponseEntity.accepted()
                .build();
    }

    @PutMapping("/{pr-id}/close")
    @Operation(
            summary = "Pull Request 닫기",
            description = "지정된 Pull Request를 닫습니다. DB의 상태와 GitHub의 상태를 모두 업데이트합니다."
    )
    public ResponseEntity<Void> closePullRequest(
            @AuthenticationPrincipal CustomUserDetail userDetail,
            @PathVariable("repo-id") Long repoId,
            @PathVariable("pr-id") Long pullRequestId) {
        
        pullRequestService.closePullRequest(userDetail, repoId, pullRequestId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{pr-id}/reopen")
    @Operation(
            summary = "Pull Request 다시 열기",
            description = "닫힌 Pull Request를 엽니다. DB의 상태와 GitHub의 상태를 모두 업데이트합니다."
    )
    public ResponseEntity<Void> reopenPullRequest(
            @AuthenticationPrincipal CustomUserDetail userDetail,
            @PathVariable("repo-id") Long repoId,
            @PathVariable("pr-id") Long pullRequestId) {

        pullRequestService.reopenPullRequest(userDetail, repoId, pullRequestId);
        return ResponseEntity.ok().build();
    }

}
