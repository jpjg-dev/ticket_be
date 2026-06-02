# Backend Issue List

TicketLedger 백엔드에서 아직 추적해야 할 미해결 항목을 카테고리별로 정리한다.
완료된 항목은 이 문서에서 제거한다.

## 1. 예약/좌석/시간 정책

- [ ] 시간 저장 및 표시 정책 전환
  - 현재 서비스 대상은 국내 공연/국내 사용자로 한정하고, 화면 표시는 `Asia/Seoul` 기준으로 제공한다.
  - 예약 생성, 결제 승인/취소, 예약 만료처럼 실제 발생한 시점 데이터는 DB에 UTC 기준으로 저장하도록 `LocalDateTime` 사용 범위를 검토하고 `Instant` 중심 전환을 진행한다.
  - API 응답은 UTC 기준 ISO 시각을 전달하고 프론트가 `Asia/Seoul`로 표시하는 방향을 기준으로 한다.
  - 향후 해외 공연 또는 해외 사용자 지원 시 공연장 timezone과 사용자 timezone 저장 정책을 별도로 추가 검토한다.

## 2. 결제/상태 전이

- [ ] Toss Payments 외부 호출 장애 대응 개선
  - 후보: timeout, retry, circuit breaker, 장애 로그 표준화.
  - 외부 호출 대상이 늘어나면 Resilience4j, OpenFeign, WebClient 전환 여부를 재검토한다.
