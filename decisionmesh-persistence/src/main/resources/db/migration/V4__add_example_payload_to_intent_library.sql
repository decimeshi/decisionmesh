-- ============================================================
-- V4__add_example_payload_to_intent_library.sql
-- Adds example_payload JSONB column to intent_library.
-- Data is populated by IntentLibraryBootstrap on startup
-- from intent-library-fintech.json (examplePayload field).
-- ============================================================
ALTER TABLE intent_library
    ADD COLUMN IF NOT EXISTS example_payload JSONB;