package com.jipi.ticket_ledger.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfig {

    // 만료/grace 시간 판정의 단일 시간 소스.
    // 운영은 서비스 타임존 기준 시스템 시계를 쓰고, 테스트는 Clock.fixed 로 교체해 시간 흐름을 결정적으로 재현한다.
    @Bean
    public Clock clock(@Value("${app.time.service-zone:Asia/Seoul}") String serviceZoneId) {
        return Clock.system(ZoneId.of(serviceZoneId));
    }
}
