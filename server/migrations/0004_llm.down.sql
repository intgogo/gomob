BEGIN;
DROP TABLE IF EXISTS llm_call_logs;
DROP TRIGGER IF EXISTS trg_llm_templates_updated_at ON llm_templates;
DROP FUNCTION IF EXISTS touch_llm_templates_updated_at();
DROP TABLE IF EXISTS llm_templates;
COMMIT;
