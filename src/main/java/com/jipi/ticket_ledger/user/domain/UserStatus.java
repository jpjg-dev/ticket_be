package com.jipi.ticket_ledger.user.domain;

public enum UserStatus {
    ACTIVE,    //활성화된 사용자
    DORMANT,   //휴면 사용자 (일정 기간 동안 활동이 없는 사용자)
    WITHDRAWN  //탈퇴한 사용자
}
