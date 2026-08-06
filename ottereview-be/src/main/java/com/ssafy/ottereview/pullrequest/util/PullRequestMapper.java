package com.ssafy.ottereview.pullrequest.util;

import com.ssafy.ottereview.branch.entity.Branch;
import com.ssafy.ottereview.preparation.dto.PrUserInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestCommitInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestFileInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestUserInfo;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestDetailResponse;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestResponse;
import com.ssafy.ottereview.pullrequest.entity.PrState;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.repo.dto.RepoResponse;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.user.entity.User;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PullRequestMapper {
    
    public PullRequestDetailResponse pullRequestToDetailResponse(PullRequest pr,
            Branch baseBranch , Branch headBranch,
            List<PullRequestFileInfo> pullRequestFileChanges,
            List<PullRequestCommitInfo> pullRequestCommitInfos) {
        return PullRequestDetailResponse.builder()
                .id(pr.getId())
                .githubId(pr.getGithubId())
                .commitSha(pr.getCommitSha())
                .githubPrNumber(pr.getGithubPrNumber())
                .title(pr.getTitle())
                .body(pr.getBody())
                .state(pr.getState().toString())
                .merged(pr.getMerged())
                .headBranch(headBranch)
                .head(pr.getHead())
                .baseBranch(baseBranch)
                .base(pr.getBase())
                .mergeable(pr.getMergeable())
                .githubCreatedAt(pr.getGithubCreatedAt())
                .githubUpdatedAt(pr.getGithubUpdatedAt())
                .commitCnt(pr.getCommitCnt())
                .changedFilesCnt(pr.getChangedFilesCnt())
                .commentCnt(pr.getCommentCnt())
                .reviewCommentCnt(pr.getReviewCommentCnt())
                .htmlUrl(pr.getHtmlUrl())
                .patchUrl(pr.getPatchUrl())
                .issueUrl(pr.getIssueUrl())
                .diffUrl(pr.getDiffUrl())
                .summary(pr.getSummary())
                .approveCnt(pr.getApproveCnt())
                .author(PullRequestUserInfo.fromEntity(pr.getAuthor()))
                .repo(RepoResponse.fromEntity(pr.getRepo()))
                .files(pullRequestFileChanges)
                .commits(pullRequestCommitInfos)
                .build();
    }
    
    public PullRequest detailResponseToEntity(PullRequestDetailResponse resp, Repo repo, User author) {
        return PullRequest.builder()
                .githubId(resp.getGithubId())
                .githubPrNumber(resp.getGithubPrNumber())
                .commitSha(resp.getCommitSha())
                .title(resp.getTitle())
                .body(resp.getBody())
                .state(PrState.fromGithubState(resp.getState(), resp.getMerged()))
                .author(author)
                .merged(resp.getMerged())
                .base(resp.getBase())
                .head(resp.getHead())
                .mergeable(resp.getMergeable() != null && resp.getMergeable())
                .githubCreatedAt(resp.getGithubCreatedAt())
                .githubUpdatedAt(resp.getGithubUpdatedAt())
                .commitCnt(resp.getCommitCnt())
                .changedFilesCnt(resp.getChangedFilesCnt())
                .commentCnt(resp.getCommentCnt())
                .reviewCommentCnt(resp.getReviewCommentCnt())
                .htmlUrl(resp.getHtmlUrl())
                .patchUrl(resp.getPatchUrl())
                .issueUrl(resp.getIssueUrl())
                .diffUrl(resp.getDiffUrl())
                .summary(resp.getSummary())
                .approveCnt(resp.getApproveCnt())
                .repo(repo)
                .build();
    }
    
    public PullRequestResponse PullRequestToResponse(PullRequest pr) {
        return PullRequestResponse.builder()
                .id(pr.getId())
                .githubId(pr.getGithubId())
                .githubPrNumber(pr.getGithubPrNumber())
                .commitSha(pr.getCommitSha())
                .title(pr.getTitle())
                .body(pr.getBody())
                .summary(pr.getSummary())
                .approveCnt(pr.getApproveCnt())
                .state(pr.getState().toString())
                .merged(pr.getMerged())
                .mergeable(pr.getMergeable() != null && pr.getMergeable())
                .head(pr.getHead())
                .base(pr.getBase())
                .commitCnt(pr.getCommitCnt())
                .changedFilesCnt(pr.getChangedFilesCnt())
                .commentCnt(pr.getCommentCnt())
                .reviewCommentCnt(pr.getReviewCommentCnt())
                .githubCreatedAt(pr.getGithubCreatedAt())
                .githubUpdatedAt(pr.getGithubUpdatedAt())
                .repo(RepoResponse.fromEntity(pr.getRepo()))
                .author(convertToPrUserInfo(pr.getAuthor()))
                .build();
    }
    
    public PrUserInfo convertToPrUserInfo(User user) {
        return PrUserInfo.builder()
                .id(user.getId())
                .githubUsername(user.getGithubUsername())
                .githubEmail(user.getGithubEmail())
                .build();
    }
}
