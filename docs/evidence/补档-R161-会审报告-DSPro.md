# 会审报告 R-161 · DS V4 Pro 单方评议

**结论：有条件通过 + 必修清单（无 MAJOR，6 处 MINOR）。** 根因四锚全部属实，六方案与 demo 对齐，基线 265/265·1103 成立；修 6 处精度/覆盖后即可定稿。

## 锚点核验（4 根因 + 支持锚，全部准确 ✓）
- RC-1：指纹 watch `AiPanel.vue:1041-1050`（末段口径 :1042-1047）+ `parts.ts:90-103` upsertToolPart `splice(index,1,...)` :94 就地替换、length 不变 ✓
- RC-2：`scrollBottom` :741-752 nextTick 单快照 + :1052 setTimeout(60) hack ✓；消息容器无 RO（全仓仅 CollapsibleText.vue:42 / SpreadGrid.vue:85）✓
- RC-3：32px 闸 :496（唯一置 false 点）✓
- 一值双用：`BOTTOM_THRESHOLD_PX=32` :489 → :496（闸）+ :499 `showBackToBottom=!followBottom` → :392 ✓
- 支持：send force :894/:906/:912、expanded watch :1052、onMounted :1078、onBeforeUnmount :1081、容器 :118、浮钮 :392-399、backToBottom :502-505 ✓

## MINOR 必修清单
- **M1 回声闸·overflow-anchor 未点名**：主流路径（底部追加）锚点在视口上方、追加在其下，浏览器不调 scrollTop，判据稳健（demo `_trace2` 已实证回声跳过）；残余风险 = 内容插到锚点上方时 scrollTop 被微调→`scrollTop≠落点`→回声漏判→RC-3 型误锁。卡 §四风险2 未点名。改：判据用容差（demo 已 `<1px`，卡文「精确等于」应改「≤1px」）；收口前 `_trace` 复验 overflow-anchor 场景。
- **M2 smooth 与 spec 同步断言冲突**：方案#4「behavior:smooth」若走 `scrollTo({behavior:'smooth'})`，jsdom 不同步更新 scrollTop，破坏 :600-620/:801-826 的 `scrollTop===1000` 断言；demo 实为 CSS `scroll-behavior:smooth`（`.smooth` 类）+ 瞬时 `scrollTop=scrollHeight`，jsdom 下 CSS 平滑无效、scrollTop 仍同步，断言可保。施工卡须明确走 CSS 方式（或改异步断言）。
- **M3 滞回态须显式重置**：拆分后 `showBackToBottom` 非 `!followBottom` 纯计算，而是带滞回的独立态；backToBottom 须显式置 false（demo 已 `remove('show')`），卡文仅提「置 followBottom=true」未点名，否则 :600-620「点击后钮消失」不成立。
- **M4 spec 只钉 64px 未钉 24px 滞回带**：新增例 60/100 只钉出钮阈值两侧；未钉「出钮后回滚 50px 仍显、<24px 才隐」的滞回语义（demo 已实测 90→50→10 链）。建议补一例（jsdom 注入 scrollTop + 手动 onMsgScroll，无需布局）。
- **M5 指纹 watch 去留未定**：RO 覆盖后指纹 watch 冗余（文本增高 RO 已覆盖；status/pendingCall 翻转不增高、无需重钉）；保留无害（双重 pinBottom 幂等），施工卡应明确去留。:1052 hack 必须撤（方案已明确）。
- **M6 focus4 口径澄清（非缺陷）**：会审卡所引「max(200, 0.5×clientHeight)」为 demo 旧口径，已被用户直裁 64/24 滞回常量取代；64/24 系业界标准（与 clientHeight 无关），无矮面板/全屏体感问题。建议矮面板场景补一负例防回归。

## 其余核验
- 六方案 vs demo：RO 重钉 / 回声闸 / 64-24 滞回 / smooth / role="log" 均对齐 ✓
- 范围外合理：N-new pill 不做（用户直裁）、速度感知脱离挂账、CollapsibleText RO 兜底归 issue #14 ✓
- spec 可断言性：RO 靠 setup.ts no-op 桩 + spec 可控桩驱动回调 ✓；「followBottom=false ⇒ 不钉」补全 R-154 登记缺口（补档-R154-前端-复核报告-DSPro.md:34）✓
- 门禁：265/265（R-159 未施工，基线仍 265）、ls-files 1103 ✓、后端零动 ✓

## DC-19 署名
模型 `DeepSeek/deepseek-v4-pro` · 会话 `session_f0e62484-c665-4ba8-b87b-8cfdf792d4ef` · wire `agents/main/wire.jsonl` profile.bind modelAlias 同左实证。
