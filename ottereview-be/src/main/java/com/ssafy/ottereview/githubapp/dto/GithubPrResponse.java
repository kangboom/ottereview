package com.ssafy.ottereview.githubapp.dto;

import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHUser;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@Slf4j
public class GithubPrResponse {

    private Long githubId;
    private Integer githubPrNumber;
    private String commitSha;
    private String title;
    private String body;
    private String state;
    private GHUser author;
    private List<GHUser> assignees;
    private List<GHUser> requestedReviewers;
    private Boolean merged;
    private String base;
    private String head;
    private Boolean mergeable;
    private LocalDateTime githubCreatedAt;
    private LocalDateTime githubUpdatedAt;

    private Integer commitCnt;
    private Integer changedFilesCnt;
    private Integer commentCnt;
    private Integer reviewCommentCnt;

    private URL htmlUrl;
    private URL patchUrl;
    private URL issueUrl;
    private URL diffUrl;
    
    public static GithubPrResponse from(GHPullRequest ghPullRequest) {
        try {
            return GithubPrResponse.builder()
                    .githubId(ghPullRequest.getId())
                    .githubPrNumber(ghPullRequest.getNumber())
                    .commitSha(ghPullRequest.getHead().getSha())
                    .title(ghPullRequest.getTitle())
                    .body(ghPullRequest.getBody())
                    .state(ghPullRequest.getState().name())
                    .author(ghPullRequest.getUser())
                    .assignees(ghPullRequest.getAssignees())
                    .requestedReviewers(ghPullRequest.getRequestedReviewers())
                    .merged(ghPullRequest.isMerged())
                    .base(ghPullRequest.getBase().getRef())
                    .head(ghPullRequest.getHead().getRef())
                    .mergeable(ghPullRequest.getMergeable())
                    .githubCreatedAt(convertToLocalDateTime(ghPullRequest.getCreatedAt()))
                    .githubUpdatedAt(convertToLocalDateTime(ghPullRequest.getUpdatedAt()))
                    .commitCnt(ghPullRequest.getCommits())
                    .changedFilesCnt(ghPullRequest.getChangedFiles())
                    .commentCnt(ghPullRequest.getCommentsCount())
                    .reviewCommentCnt(ghPullRequest.getReviewComments())
                    .htmlUrl(ghPullRequest.getHtmlUrl())
                    .patchUrl(ghPullRequest.getPatchUrl())
                    .issueUrl(ghPullRequest.getIssueUrl())
                    .diffUrl(ghPullRequest.getDiffUrl())
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("GitHub API 호출 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    // 날짜 변환 유틸리티 메서드
    private static LocalDateTime convertToLocalDateTime(Object dateObj) {
        if (dateObj == null) {
            return null;
        }
        try {
            // GitHub API는 java.util.Date를 반환
            if (dateObj instanceof java.time.Instant) {
                java.time.Instant date = (java.time.Instant) dateObj;
                return date.atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime();
            }
            
            // 만약 다른 타입이라면 toString()으로 파싱 시도
            String dateStr = dateObj.toString();
            return LocalDateTime.parse(dateStr.replace("Z", ""));

        } catch (Exception e) {
            // 변환 실패시 현재 시간 반환
            return LocalDateTime.now();
        }
    }
}
