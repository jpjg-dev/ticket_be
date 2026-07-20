package com.jipi.ticket_ledger.featureflag.application;

public class FeatureFlagVersionConflictException extends IllegalStateException {

    public FeatureFlagVersionConflictException() {
        super("피처 플래그가 다른 요청에서 변경되었습니다. 최신 상태를 다시 확인해 주세요.");
    }
}
