# 코드 아키텍처

## 목적

현재 백엔드는 하나의 Spring Boot 애플리케이션이지만, 외부 PG·JWT·Redis 같은 구현 세부사항이 예약·결제 정책으로 침투하지 않도록 계층 의존성을 분리합니다. 이후 서비스를 분리하더라도 도메인 상태 전이와 유스케이스를 재사용할 수 있는 경계를 목표로 합니다.

## 의존 방향

```text
presentation -> application -> domain
                         |
                         v
                    port(out)
                         ^
                         |
                  infrastructure
```

- `presentation`은 HTTP 검증과 요청/응답 변환을 담당합니다.
- `application`은 유스케이스 조합과 트랜잭션 경계를 담당합니다.
- `domain`은 상태 전이와 불변식을 담당합니다.
- `application.port.out`은 외부 시스템에 필요한 최소 계약만 정의합니다.
- `infrastructure`는 Toss, JWT, Redis 등 기술별 어댑터를 구현합니다.

## 적용한 구조 개선

| 영역 | 변경 | 유지한 정책 |
| --- | --- | --- |
| 결제 PG | `PaymentGateway` 출력 포트와 PG 중립 상태를 도입했습니다. | PG 호출 전후 트랜잭션 분리, 멱등키, `CONFIRMING`/`CANCELING` 보정 |
| 인증 | `TokenProvider`, `TokenHashEncoder`로 JWT 구현을 분리했습니다. | RTR, 조건부 Refresh Token 소비, 토큰 원문 미저장 |
| 공연/좌석 조회 | `EventQueryService`, `SeatQueryService`로 책임을 나눴습니다. | 미래 회차 필터, 만료 선처리, SoldOut 판정, 가용 좌석 조회 |
| 출력 모델 | 조회 결과를 `application.model`로 이동했습니다. | 기존 HTTP JSON 필드 |
| 관측성 | 보정 메트릭을 `application.observability`로 이동했습니다. | 기존 Prometheus 지표명과 태그 |

## 구조 규칙

ArchUnit 테스트가 다음 규칙을 검증합니다.

1. `application`은 `infrastructure`, `presentation`에 의존하지 않습니다.
2. `domain`은 `application`, `infrastructure`, `presentation`에 의존하지 않습니다.

인터페이스는 모든 서비스에 일괄 적용하지 않습니다. 외부 시스템 교체 가능성이나 계층 역전이 필요한 경계에만 두고, 내부 유스케이스는 구체 클래스로 유지합니다.

## 트랜잭션 원칙

- 외부 PG 호출은 DB 트랜잭션 밖에서 수행합니다.
- PG 호출 전후 상태 변경은 별도 Spring Bean의 public `@Transactional` 메서드로 유지합니다.
- 상태 전이는 엔티티 메서드를 통해 수행하고 JPA 변경 감지로 반영합니다.
- 좌석 잠금 순서와 예약 그룹 단위 전체 성공/실패 정책은 변경하지 않습니다.
