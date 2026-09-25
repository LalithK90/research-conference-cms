-- Default production-profile seed data: a minimal, generic starting conference.
-- Kept deliberately non-institution-specific (this is a reusable, self-hosted product,
-- not tied to any one university or event) -- replace or clear before a real deployment.
--
-- Schema is Hibernate-generated (spring.jpa.hibernate.ddl-auto=update in the default
-- profile), so this file only needs to match the current @Entity columns, not define them.
--
-- Runs on every boot (spring.sql.init.mode=always, required so it runs against a real
-- MySQL datasource at all -- see application.properties). The WHERE NOT EXISTS guard
-- keeps a restart from inserting a second copy of the sample conference; it is not a
-- full migration-safe idempotency mechanism, so replace or clear this file before a
-- real deployment rather than relying on it long-term.

INSERT INTO conferences (title, venue, start_date, end_date, is_active, blind_review, contact_email, created_at, updated_at)
SELECT 'Sample Research Conference', 'Your Venue Here', '2026-01-01', '2026-01-03', true, false, 'info@example.org', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM conferences WHERE title = 'Sample Research Conference');

SET @conference_id = (SELECT id FROM conferences WHERE title = 'Sample Research Conference' LIMIT 1);

INSERT INTO sub_themes (conference_id, name, description, created_at, updated_at)
SELECT @conference_id, 'Track A', 'Replace with your conference''s first track/theme.', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sub_themes WHERE conference_id = @conference_id AND name = 'Track A');

INSERT INTO sub_themes (conference_id, name, description, created_at, updated_at)
SELECT @conference_id, 'Track B', 'Replace with your conference''s second track/theme.', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sub_themes WHERE conference_id = @conference_id AND name = 'Track B');

-- Admin account is no longer seeded here. FirstRunAdminInitializer (see
-- org.confcms.cms.security) creates asakahatapitiya@gmail.com with a randomly
-- generated password on first boot when no ADMIN user exists yet, and logs the
-- password once. See README.md for retrieval instructions.
