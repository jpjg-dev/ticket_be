-- created_at(실제 발생 시각) + booking_open_at(예매 오픈, 절대 시점)을 Instant 기준 timestamptz 로 변환한다.
-- 기존 값은 Asia/Seoul 벽시계로 저장돼 있었으므로 그 기준으로 해석해 UTC instant 로 바꾼다.
-- 공연 시작/종료(start_at/end_at)는 공연장 로컬 시간이라 그대로 TIMESTAMP WITHOUT TIME ZONE 으로 둔다.
ALTER TABLE events
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN booking_open_at TYPE TIMESTAMP WITH TIME ZONE USING booking_open_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE schedules
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE seats
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE users
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMP WITH TIME ZONE USING updated_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE refresh_tokens
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN expires_at TYPE TIMESTAMP WITH TIME ZONE USING expires_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN last_used_at TYPE TIMESTAMP WITH TIME ZONE USING last_used_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN revoked_at TYPE TIMESTAMP WITH TIME ZONE USING revoked_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE reservation_groups
    ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN expires_at TYPE TIMESTAMP WITH TIME ZONE USING expires_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE reservations
    ALTER COLUMN reserved_at TYPE TIMESTAMP WITH TIME ZONE USING reserved_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN expires_at TYPE TIMESTAMP WITH TIME ZONE USING expires_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN canceled_at TYPE TIMESTAMP WITH TIME ZONE USING canceled_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE payments
    ALTER COLUMN requested_at TYPE TIMESTAMP WITH TIME ZONE USING requested_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN confirming_at TYPE TIMESTAMP WITH TIME ZONE USING confirming_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN approved_at TYPE TIMESTAMP WITH TIME ZONE USING approved_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN canceled_at TYPE TIMESTAMP WITH TIME ZONE USING canceled_at AT TIME ZONE 'Asia/Seoul';
