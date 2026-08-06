package com.ssafy.ottereview.pullrequest.dto.info;

import com.ssafy.ottereview.pullrequest.entity.PullRequest;

public record PullRequestSyncResult(PullRequest pullRequest, boolean created) {
}
