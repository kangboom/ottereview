package com.ssafy.ottereview.pullrequest.entity;

public enum PrState {
    OPEN,
    CLOSED,
    MERGED;

    public static PrState fromGithubState(String state, Boolean merged) {
        if ("closed".equalsIgnoreCase(state)) {
            return (merged != null && merged) ? MERGED : CLOSED;
        }
        return OPEN;
    }
}
