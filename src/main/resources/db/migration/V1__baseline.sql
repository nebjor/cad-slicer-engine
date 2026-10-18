-- Baseline: establishes Flyway's schema history for the slicercore database.
-- The quote path is stateless today, so no application tables exist yet; the first table
-- (orders, roadmap phase 7) arrives as V2. Keeping V1 empty makes the history start at a
-- known point instead of retrofitting a baseline later.
SELECT 1;
