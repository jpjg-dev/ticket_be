INSERT INTO users (
    email,
    password,
    name,
    created_at,
    updated_at,
    status,
    role
)
SELECT
    'perf-user-' || LPAD(sequence::text, 5, '0') || '@email.com',
    '$2a$10$performanceOnlyPasswordHashNotUsedForLogin000000000000000',
    'perf-user-' || LPAD(sequence::text, 5, '0'),
    NOW(),
    NULL,
    'ACTIVE',
    'ROLE_USER'
FROM generate_series(1, 10000) AS sequence
ON CONFLICT (email) DO NOTHING;

SELECT id
FROM users
WHERE email LIKE 'perf-user-%@email.com'
ORDER BY email
LIMIT 10000;
