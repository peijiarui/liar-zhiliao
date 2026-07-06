-- =============================================================================
-- liar-zhiliao Seed Data
-- Target Database: PostgreSQL 14+
--
-- BCrypt password hashes (rounds = 10):
--   admin123  → $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
--   123456    → $2a$10$.J9Dk5kBT0UQxYPfqYq3s.DGQPnwY3Y5GqYI71G2QrMnKN9JGKQTa
-- =============================================================================

-- 1. Departments
-- =============================================================================
-- All departments are root-level (parent_id = NULL) for MVP.
-- The UNIQUE (tenant_id, name) constraint prevents duplicate inserts.

INSERT INTO departments (name, parent_id, tenant_id)
VALUES ('技术部', NULL, 'default'),
       ('产品部', NULL, 'default'),
       ('运营部', NULL, 'default')
ON CONFLICT (tenant_id, name) DO NOTHING;

-- 2. Users
-- =============================================================================
-- dept_id is resolved via subquery to avoid hard-coded IDs.
-- admin gets the ADMIN role; the other two get USER.
-- The UNIQUE (username) constraint prevents duplicate inserts.

INSERT INTO users (username, password_hash, dept_id, role, tenant_id)
VALUES ('admin',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        (SELECT id FROM departments WHERE name = '技术部' AND tenant_id = 'default'), 'ADMIN', 'default'),
       ('zhangsan', '$2a$10$.J9Dk5kBT0UQxYPfqYq3s.DGQPnwY3Y5GqYI71G2QrMnKN9JGKQTa',
        (SELECT id FROM departments WHERE name = '产品部' AND tenant_id = 'default'), 'USER',  'default'),
       ('lisi',     '$2a$10$.J9Dk5kBT0UQxYPfqYq3s.DGQPnwY3Y5GqYI71G2QrMnKN9JGKQTa',
        (SELECT id FROM departments WHERE name = '运营部' AND tenant_id = 'default'), 'USER',  'default')
ON CONFLICT (username) DO NOTHING;
