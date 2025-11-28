-- H2-compatible schema for development profile
CREATE TABLE IF NOT EXISTS conferences (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  venue VARCHAR(255) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  is_active BOOLEAN NOT NULL,
  logo_url VARCHAR(255),
  contact_email VARCHAR(255),
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);

-- Insert a sample conference for UI/testing
INSERT INTO conferences (title, venue, start_date, end_date, is_active, logo_url, contact_email, created_at, updated_at)
VALUES ('Research Conference 2025', 'Virtual / Example Venue', '2025-11-01', '2025-11-30', TRUE, 'https://example.com/logo.png', 'info@example.org', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

-- Users table (for references and history). Passwords are left blank for dev (DevSecurityConfig provides in-memory users).
CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255),
  full_name VARCHAR(255) NOT NULL,
  role VARCHAR(50) NOT NULL,
  provider VARCHAR(50),
  provider_id VARCHAR(255),
  enabled BOOLEAN NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);

-- Password hashes generated with BCrypt (work factor ~10) for dev passwords
-- admin@example.com / admin
-- reviewer@example.com / reviewer
-- author@example.com / author
INSERT INTO users (email, password_hash, full_name, role, provider, enabled, created_at, updated_at)
VALUES
('admin@example.com', '$2a$10$QxQmTnWb0k1uL3s4R6zQ5e8l7OqUu3R2m3tv5U6wOeJcT3r2A1y2K', 'Dev Admin', 'ADMIN', 'local', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()),
('reviewer@example.com', '$2a$10$1PjvYwHkS8dV9bQe4r7uKuVfQeKX8f2rAqKQ7m1pWnYvT3b5Zc6rS', 'Dev Reviewer', 'REVIEWER', 'local', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()),
('author@example.com', '$2a$10$wZxYvQpL9mBnC3dE7tFhYu6pQeR8tU1lM2nO5pQzR7sT8uV1wXyZa', 'Dev Author', 'AUTHOR', 'local', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

-- Sub-themes and steering committee for the sample conference
CREATE TABLE IF NOT EXISTS sub_themes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conference_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  FOREIGN KEY (conference_id) REFERENCES conferences(id)
);

INSERT INTO sub_themes (conference_id, name, description, created_at, updated_at)
VALUES (1, 'Machine Learning', 'Applications of ML in agriculture.', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

CREATE TABLE IF NOT EXISTS steering_committee (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conference_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  role VARCHAR(255) NOT NULL,
  bio TEXT,
  image_url VARCHAR(255),
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  FOREIGN KEY (conference_id) REFERENCES conferences(id)
);

INSERT INTO steering_committee (conference_id, name, role, bio, image_url, created_at, updated_at)
VALUES (1, 'Dr. Example Chair', 'Conference Chair', 'Organizer of the sample conference.', '', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

-- Papers and related tables
CREATE TABLE IF NOT EXISTS papers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  abstract_text TEXT NOT NULL,
  submitter_id BIGINT NOT NULL,
  status VARCHAR(50) NOT NULL,
  track VARCHAR(255) NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  FOREIGN KEY (submitter_id) REFERENCES users(id)
);

INSERT INTO papers (title, abstract_text, submitter_id, status, track, created_at, updated_at)
VALUES ('Sample Paper on ML', 'This paper explores sample ML methods.', 3, 'ACCEPTED', 'Machine Learning', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

CREATE TABLE IF NOT EXISTS paper_versions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  paper_id BIGINT NOT NULL,
  version_number INT NOT NULL,
  file_path VARCHAR(1024) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  FOREIGN KEY (paper_id) REFERENCES papers(id)
);

INSERT INTO paper_versions (paper_id, version_number, file_path, original_filename, created_at, updated_at)
VALUES (1, 1, '/uploads/sample-paper.pdf', 'sample-paper.pdf', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

CREATE TABLE IF NOT EXISTS paper_authors (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  paper_id BIGINT NOT NULL,
  full_name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NOT NULL,
  affiliation VARCHAR(255) NOT NULL,
  is_presenter BOOLEAN NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  FOREIGN KEY (paper_id) REFERENCES papers(id)
);

INSERT INTO paper_authors (paper_id, full_name, email, affiliation, is_presenter, created_at, updated_at)
VALUES (1, 'Dev Author', 'author@example.com', 'Example University', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

-- Review assignments and reviews
CREATE TABLE IF NOT EXISTS review_assignments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  paper_id BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  assigned_at TIMESTAMP NOT NULL,
  due_date TIMESTAMP,
  status VARCHAR(50) NOT NULL,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  FOREIGN KEY (paper_id) REFERENCES papers(id),
  FOREIGN KEY (reviewer_id) REFERENCES users(id)
);

INSERT INTO review_assignments (paper_id, reviewer_id, assigned_at, due_date, status, created_at, updated_at)
VALUES (1, 2, CURRENT_TIMESTAMP(), NULL, 'PENDING', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

CREATE TABLE IF NOT EXISTS reviews (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  paper_id BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  score INT NOT NULL,
  comments TEXT,
  confidential_comments TEXT,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  FOREIGN KEY (paper_id) REFERENCES papers(id),
  FOREIGN KEY (reviewer_id) REFERENCES users(id)
);

INSERT INTO reviews (paper_id, reviewer_id, score, comments, confidential_comments, created_at, updated_at)
VALUES (1, 2, 4, 'Promising results in sample domain.', 'Confidential note.', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

-- Registrations (kept minimal for dev)
CREATE TABLE IF NOT EXISTS registrations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conference_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  participant_type VARCHAR(100),
  amount DECIMAL(10,2),
  payment_status VARCHAR(50),
  invoice_path VARCHAR(1024),
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  FOREIGN KEY (conference_id) REFERENCES conferences(id),
  FOREIGN KEY (user_id) REFERENCES users(id)
);

INSERT INTO registrations (conference_id, user_id, participant_type, amount, payment_status, invoice_path, created_at, updated_at)
VALUES (1, 3, 'Academic', 0.00, 'PAID', NULL, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

-- ==============================
-- Additional demo data (history)
-- ==============================

-- More conferences (kept inactive for history)
INSERT INTO conferences (title, venue, start_date, end_date, is_active, logo_url, contact_email, created_at, updated_at)
VALUES ('Research Conference 2026', 'Colombo, Sri Lanka', '2026-08-10', '2026-08-12', FALSE, 'https://example.com/logo-2026.png', 'info2026@example.org', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

INSERT INTO conferences (title, venue, start_date, end_date, is_active, logo_url, contact_email, created_at, updated_at)
VALUES ('Research Conference 2024', 'Bangkok, Thailand', '2024-09-05', '2024-09-07', FALSE, 'https://example.com/logo-2024.png', 'info2024@example.org', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

-- Sub-themes for 2026 and 2024
INSERT INTO sub_themes (conference_id, name, description, created_at, updated_at)
SELECT c.id, 'Sustainability', 'Sustainable practices in agri-food systems.', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM conferences c WHERE c.title='Research Conference 2026';

INSERT INTO sub_themes (conference_id, name, description, created_at, updated_at)
SELECT c.id, 'Data Science', 'Data and analytics for agriculture.', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM conferences c WHERE c.title='Research Conference 2024';

-- Steering committee for 2026 and 2024
INSERT INTO steering_committee (conference_id, name, role, bio, image_url, created_at, updated_at)
SELECT c.id, 'Prof. S. Example', 'Program Chair', 'Program Chair for 2026.', '', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM conferences c WHERE c.title='Research Conference 2026';

INSERT INTO steering_committee (conference_id, name, role, bio, image_url, created_at, updated_at)
SELECT c.id, 'Dr. P. Example', 'Organizing Chair', 'Organizer for 2024.', '', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM conferences c WHERE c.title='Research Conference 2024';

-- More users (for assignments and richer demo)
INSERT INTO users (email, password_hash, full_name, role, provider, enabled, created_at, updated_at)
VALUES
('chair@example.com', '', 'Track Chair', 'ADMIN', 'local', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()),
('author2@example.com', '', 'Second Author', 'AUTHOR', 'local', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()),
('reviewer2@example.com', '', 'Second Reviewer', 'REVIEWER', 'local', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP());

-- Additional papers by authors
INSERT INTO papers (title, abstract_text, submitter_id, status, track, created_at, updated_at)
SELECT 'Precision Agriculture with Drones', 'Explores drone-based crop monitoring.', u.id, 'UNDER_REVIEW', 'Sustainability', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM users u WHERE u.email='author2@example.com';

INSERT INTO papers (title, abstract_text, submitter_id, status, track, created_at, updated_at)
SELECT 'AI for Crop Yield Prediction', 'Predictive models for yield.', u.id, 'SUBMITTED', 'Data Science', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM users u WHERE u.email='author@example.com';

-- Versions for the new papers
INSERT INTO paper_versions (paper_id, version_number, file_path, original_filename, created_at, updated_at)
SELECT p.id, 1, '/uploads/drones-v1.pdf', 'drones-v1.pdf', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p WHERE p.title='Precision Agriculture with Drones';

INSERT INTO paper_versions (paper_id, version_number, file_path, original_filename, created_at, updated_at)
SELECT p.id, 2, '/uploads/drones-v2.pdf', 'drones-v2.pdf', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p WHERE p.title='Precision Agriculture with Drones';

INSERT INTO paper_versions (paper_id, version_number, file_path, original_filename, created_at, updated_at)
SELECT p.id, 1, '/uploads/yield-v1.pdf', 'yield-v1.pdf', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p WHERE p.title='AI for Crop Yield Prediction';

-- Authors for the new papers
INSERT INTO paper_authors (paper_id, full_name, email, affiliation, is_presenter, created_at, updated_at)
SELECT p.id, 'Second Author', 'author2@example.com', 'AgriTech Labs', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p WHERE p.title='Precision Agriculture with Drones';

INSERT INTO paper_authors (paper_id, full_name, email, affiliation, is_presenter, created_at, updated_at)
SELECT p.id, 'Dev Author', 'author@example.com', 'Example University', TRUE, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p WHERE p.title='AI for Crop Yield Prediction';

-- Assign reviewers and add reviews
INSERT INTO review_assignments (paper_id, reviewer_id, assigned_at, due_date, status, created_at, updated_at)
SELECT p.id, u.id, CURRENT_TIMESTAMP(), NULL, 'PENDING', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p JOIN users u ON u.email='reviewer2@example.com'
WHERE p.title='Precision Agriculture with Drones';

INSERT INTO reviews (paper_id, reviewer_id, score, comments, confidential_comments, created_at, updated_at)
SELECT p.id, u.id, 5, 'Excellent clarity and methodology.', 'Consider blind spots in data.', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p JOIN users u ON u.email='reviewer2@example.com'
WHERE p.title='Precision Agriculture with Drones';

INSERT INTO review_assignments (paper_id, reviewer_id, assigned_at, due_date, status, created_at, updated_at)
SELECT p.id, u.id, CURRENT_TIMESTAMP(), NULL, 'PENDING', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p JOIN users u ON u.email='reviewer@example.com'
WHERE p.title='AI for Crop Yield Prediction';

INSERT INTO reviews (paper_id, reviewer_id, score, comments, confidential_comments, created_at, updated_at)
SELECT p.id, u.id, 3, 'Good baseline; needs more experiments.', 'Check data leakage.', CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM papers p JOIN users u ON u.email='reviewer@example.com'
WHERE p.title='AI for Crop Yield Prediction';

-- Registrations for the additional users to 2026 conference
INSERT INTO registrations (conference_id, user_id, participant_type, amount, payment_status, invoice_path, created_at, updated_at)
SELECT c.id, u.id, 'Academic', 0.00, 'PENDING', NULL, CURRENT_TIMESTAMP(), CURRENT_TIMESTAMP()
FROM conferences c JOIN users u ON u.email='author2@example.com'
WHERE c.title='Research Conference 2026';

