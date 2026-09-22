-- Default production-profile seed data: a minimal, generic starting conference.
-- Kept deliberately non-institution-specific (this is a reusable, self-hosted product,
-- not tied to any one university or event) -- replace or clear before a real deployment.
--
-- Schema is Hibernate-generated (spring.jpa.hibernate.ddl-auto=update in the default
-- profile), so this file only needs to match the current @Entity columns, not define them.

INSERT INTO conferences (title, venue, start_date, end_date, is_active, blind_review, contact_email, created_at, updated_at)
VALUES ('Sample Research Conference', 'Your Venue Here', '2026-01-01', '2026-01-03', true, false, 'info@example.org', NOW(), NOW());

SET @conference_id = LAST_INSERT_ID();

INSERT INTO sub_themes (conference_id, name, description, created_at, updated_at) VALUES
(@conference_id, 'Track A', 'Replace with your conference''s first track/theme.', NOW(), NOW()),
(@conference_id, 'Track B', 'Replace with your conference''s second track/theme.', NOW(), NOW());

-- Default admin account. CHANGE THIS PASSWORD IMMEDIATELY after first login --
-- password hash below is for 'ChangeMe123!' (freshly generated via BCryptPasswordEncoder,
-- not reused from any other seeded credential), included only so the seeded account is
-- usable on first boot, not as a credential meant to remain in production use.
INSERT INTO users (email, password_hash, full_name, role, enabled, created_at, updated_at) VALUES
('admin@example.org', '$2a$10$LxEtwjAhmgMiiCNTfiAQDeRij1FHBfgs4f7SBPy23.JmMUjXPN/fC', 'System Administrator', 'ADMIN', true, NOW(), NOW());
