-- 目标级 OpenAI 兼容请求扩展；null = 不追加扩展。
ALTER TABLE ai_model ADD COLUMN IF NOT EXISTS request_extra_body TEXT;
