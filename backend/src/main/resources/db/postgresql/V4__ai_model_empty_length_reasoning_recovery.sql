-- Optional per-target reasoning policy used only for one empty-LENGTH recovery attempt.
ALTER TABLE ai_model ADD COLUMN IF NOT EXISTS empty_length_reasoning_recovery TEXT;
