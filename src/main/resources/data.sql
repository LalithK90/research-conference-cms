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

-- Admin account is no longer seeded here. FirstRunAdminInitializer (see
-- org.confcms.cms.security) creates asakahatapitiya@gmail.com with a randomly
-- generated password on first boot when no ADMIN user exists yet, and logs the
-- password once. See README.md for retrieval instructions.
