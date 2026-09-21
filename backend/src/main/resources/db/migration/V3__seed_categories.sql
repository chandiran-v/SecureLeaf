INSERT INTO categories (id, name, slug, description) VALUES
(1, 'Technology', 'technology', 'Tech and Programming resources'),
(2, 'Business', 'business', 'Business and Finance'),
(3, 'Design', 'design', 'Design and UX')
ON CONFLICT (id) DO NOTHING;
