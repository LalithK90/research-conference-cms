-- Seed Data for International Conference on Agricultural Innovation & Food Security 2026

-- Insert Active Conference
INSERT INTO conferences (title, venue, start_date, end_date, is_active, contact_email, created_at, updated_at) 
VALUES ('International Conference on Agricultural Innovation & Food Security 2026', 
        'Bangkok, Thailand', 
        '2026-05-21', 
        '2026-05-23', 
        true, 
        'info@agriinnovation2026.org',
        NOW(), 
        NOW());

SET @conference_id = LAST_INSERT_ID();

-- Insert Sub-themes
INSERT INTO sub_themes (conference_id, name, description, created_at, updated_at) VALUES
(@conference_id, 'Climate-Smart Agriculture and Resilient Farming Systems', 'Exploring sustainable farming practices adapted to climate change', NOW(), NOW()),
(@conference_id, 'Digital Agriculture and Precision Farming', 'Leveraging technology for optimized agricultural production', NOW(), NOW()),
(@conference_id, 'Sustainable Food Systems and Circular Economy in Agriculture', 'Creating closed-loop systems for agricultural sustainability', NOW(), NOW()),
(@conference_id, 'Agri-Biotechnology and Crop Improvement', 'Advancing crop genetics and biotechnology for better yields', NOW(), NOW()),
(@conference_id, 'Water, Soil, and Nutrient Management Innovations', 'Innovative approaches to resource management in agriculture', NOW(), NOW()),
(@conference_id, 'Post-Harvest Technologies and Food Processing Innovations', 'Improving food preservation and processing methods', NOW(), NOW()),
(@conference_id, 'Agricultural Policy, Market Dynamics, and Food Security', 'Policy frameworks for ensuring global food security', NOW(), NOW()),
(@conference_id, 'Urban and Vertical Farming Innovations', 'Revolutionary approaches to urban agriculture', NOW(), NOW()),
(@conference_id, 'Agroecology, Biodiversity, and Sustainable Livestock Systems', 'Promoting biodiversity and sustainable animal husbandry', NOW(), NOW()),
(@conference_id, 'Emerging Technologies for Farm-to-Fork Traceability', 'Blockchain and IoT for transparent food supply chains', NOW(), NOW());

-- Insert Steering Committee
-- Note: Using placeholder image URLs - replace with actual images
INSERT INTO steering_committee (conference_id, name, role, bio, image_url, created_at, updated_at) VALUES
(@conference_id, 
 'Dr. Pornchai Mongkhonvanit', 
 'President, Siam University', 
 'Dr. Pornchai Mongkhonvanit serves as the President of Siam University in Thailand and is the President Emeritus of the International Association of University Presidents (IAUP). He holds leadership positions in various international academic organizations and has received numerous accolades for his contributions to higher education.', 
 'https://via.placeholder.com/300x300?text=Dr.+Pornchai', 
 NOW(), NOW()),

(@conference_id, 
 'Prof Gamini Senanayake', 
 'Vice Chancellor, SANASA Campus', 
 'Professor Gamini Senanayake is the Vice Chancellor of SANASA Campus and a renowned expert in agricultural research and education. He has extensive experience in sustainable agriculture and has published numerous research papers in leading international journals.', 
 'https://via.placeholder.com/300x300?text=Prof.+Gamini', 
 NOW(), NOW()),

(@conference_id, 
 'Dr. Duminda Jeyaranjan', 
 'Dean, Ico Siam', 
 'Dr. Duminda Jeyaranjan serves as the Dean of Ico Siam and is a distinguished academic with expertise in agricultural innovation and food security. He has been instrumental in developing sustainable agricultural programs and fostering international collaboration.', 
 'https://via.placeholder.com/300x300?text=Dr.+Duminda', 
 NOW(), NOW()),

(@conference_id, 
 'Prof (Mrs.) GAS Ginigaddara', 
 'Conference Chair', 
 'Professor G.A.S. Ginigaddara is the first female Vice Chancellor of Rajarata University of Sri Lanka and serves as Conference Chair. She is an expert in agricultural systems, sustainable agriculture, and household food security with over 21 years of experience in the Sri Lankan University system.', 
 'https://via.placeholder.com/300x300?text=Prof.+Ginigaddara', 
 NOW(), NOW());

-- Insert Users with BCrypt encoded password 'password'
INSERT INTO users (email, password_hash, full_name, role, provider, enabled, created_at, updated_at) VALUES
('admin@icosiam.org', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'System Administrator', 'ADMIN', 'local', true, NOW(), NOW()),
('reviewer1@academic.edu', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Dr. Sarah Johnson', 'REVIEWER', 'local', true, NOW(), NOW()),
('reviewer2@academic.edu', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Prof. Michael Chen', 'REVIEWER', 'local', true, NOW(), NOW()),
('reviewer3@academic.edu', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Dr. Emily Rodriguez', 'REVIEWER', 'local', true, NOW(), NOW()),
('author1@university.edu', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Dr. James Williams', 'AUTHOR', 'local', true, NOW(), NOW()),
('author2@university.edu', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Dr. Maria Garcia', 'AUTHOR', 'local', true, NOW(), NOW()),
('author3@university.edu', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Prof. David Lee', 'AUTHOR', 'local', true, NOW(), NOW());

-- Get track IDs for sample papers
SET @track1 = (SELECT id FROM sub_themes WHERE conference_id = @conference_id AND name = 'Climate-Smart Agriculture and Resilient Farming Systems' LIMIT 1);
SET @track2 = (SELECT id FROM sub_themes WHERE conference_id = @conference_id AND name = 'Digital Agriculture and Precision Farming' LIMIT 1);
SET @track3 = (SELECT id FROM sub_themes WHERE conference_id = @conference_id AND name = 'Urban and Vertical Farming Innovations' LIMIT 1);

-- Get user IDs
SET @author1 = (SELECT id FROM users WHERE email = 'author1@university.edu' LIMIT 1);
SET @author2 = (SELECT id FROM users WHERE email = 'author2@university.edu' LIMIT 1);
SET @author3 = (SELECT id FROM users WHERE email = 'author3@university.edu' LIMIT 1);

-- Insert Sample Papers
INSERT INTO papers (conference_id, title, abstract_text, submitter_id, status, track_id, created_at, updated_at) VALUES
(@conference_id, 
 'Climate-Resilient Rice Varieties for Southeast Asia', 
 'This study examines the development and field testing of climate-resilient rice varieties specifically adapted for Southeast Asian conditions. Our research demonstrates significant improvements in drought tolerance and yield stability under varying climate conditions.',
 @author1, 
 'ACCEPTED', 
 @track1, 
 NOW(), NOW()),

(@conference_id, 
 'IoT-Based Precision Irrigation Systems: A Case Study', 
 'We present a comprehensive case study of IoT-based precision irrigation systems deployed across 500 hectares of agricultural land. Results show 35% water savings and 22% yield improvement compared to traditional irrigation methods.',
 @author2, 
 'ACCEPTED', 
 @track2, 
 NOW(), NOW()),

(@conference_id, 
 'Vertical Farming Economics in Urban Centers', 
 'An economic analysis of vertical farming operations in major Asian cities, examining profitability, scalability, and sustainability metrics. Our findings suggest that vertical farms can achieve profitability within 3-5 years under optimal conditions.',
 @author3, 
 'UNDER_REVIEW', 
 @track3, 
 NOW(), NOW());
