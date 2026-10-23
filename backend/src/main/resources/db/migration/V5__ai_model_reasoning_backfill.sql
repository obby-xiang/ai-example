-- L-104：存量库能力位回填 —— ai_model.supports_reasoning。
--
-- 背景：L-91 只给"种子模型行"写 supports_reasoning=true（AiConfigService#envSeedModel），
--   而 env 种子只在 ai_provider 空表时写（strict seed）⇒ 既有库升级后不会重灌，
--   老库的 ENV_SEED 模型行仍是 false（模型选择器继续显示"不支持推理"）。本迁移把存量行回填。
--
-- 口径（三重 WHERE，只收窄、不臆断）：
--   ① 只动 source='ENV_SEED' 的供应商行下的模型 —— 用户在 UI 改过的 provider 会被
--      AiConfigWriter 翻成 source='DB'，其模型行一律不碰（用户手工改过的行不盖）；
--   ② 只动 deepseek% 模型行 —— 与本机实证会返回 reasoning_content 的模型族一致，
--      其它模型（本地/第三方）不替用户猜能力位；
--   ③ 只做 FALSE/NULL → TRUE，从不反转已是 TRUE 的行 ⇒ 重复执行结果不变（幂等安全）。
UPDATE ai_model
   SET supports_reasoning = TRUE
 WHERE (supports_reasoning = FALSE OR supports_reasoning IS NULL)
   AND model_id LIKE 'deepseek%'
   AND provider_id IN (SELECT id FROM ai_provider WHERE source = 'ENV_SEED');
