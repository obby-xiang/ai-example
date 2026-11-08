# OpenAI Response Boundary Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 防止工具调用先于后置 reasoning 执行并持久化空推理 assistant，同时保持实时文本、标准工具续轮、取消和 HITL 语义。

**Architecture:** 在 OpenAiApi 的公开流式扩展点拆分每个工具 chunk：live 部分移除 toolCalls 并立即下发，deferred 部分保存完整 toolCalls，在整次 response 正常完成后携带全文、全推理和最终 usage 释放。每次订阅独立状态，错误/取消不释放。 AiProviderRegistry 统一装配；correctionHistory 前缀独立补 ReasoningOrigin。

**Tech Stack:** Java 21、Reactor、Spring AI 1.1.7、现有 AiProviderRegistry/SpToolCallingManager。

**Spec:** agent-1120 response-boundary 设计；`docs/delivery/development-standards.md` 6.12。

## Global Constraints

- 普通测试代码不得修改，施工阶段不执行普通测试；生产 Maven compile/package 必须通过。
- 不修改 Spring AI internalStream、MessageAggregator、SpToolCallingManager、TurnTextCapture 或 ReasoningAwareOpenAiChatModel，不新增依赖或供应商分支。
- live 内容不得重复；deferred 只在正常 onComplete 释放，onError/dispose/cancel 不释放工具执行或持久化。
- 复用 OpenAiApi 已合并的 toolCalls，不手写参数拼接器；每订阅线程封闭且有界清理，不保存静态/runMap状态。
- deferred 携带整次 response 正文、推理和最后非空 usage；后置空串不得覆盖非空聚合。
- 标准 OpenAI 无扩展时不制造 reasoning_content；不合并不同工具跳、不借用其他 assistant 推理。
- 每批独立关联审查、生产编译、规范门禁后提交推送，真实验证前保持 NOT_RUN。

## Review Focus

- tool finish → reasoning C → usage → complete：C必须先被live消费，deferred含C后才执行工具。
- usage-only在tool chunk之后：deferred usage非空且token不翻倍。
- onError、取消和看门狗：deferred不得释放，状态清理且无工具副作用。
- 并行订阅和并行tool calls：不串 reasoning/toolCallId/usage，不覆盖 deferred。
- HITL return-direct和实时正文：deferred全文不得二次上屏或重复写memory。

---

### Task 1: API response barrier 与前缀来源

**Files:**
- Create: `backend/src/main/java/org/springframework/ai/openai/api/ResponseBoundOpenAiApi.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/config/AiProviderRegistry.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/pom.xml`

**Interfaces:**
- Consumes: `OpenAiApi.chatCompletionStream` 已合并的 ChatCompletionChunk、AiProviderRegistry.newApi、ResilientChatService.correctionHistory。
- Produces: 正常完成后具有完整正文/reasoning/toolCalls/usage的ChatCompletionChunk；带正确来源的纠正前缀。

- [ ] **Step 1: 实现每订阅response状态与chunk拆分**
  - live移除toolCalls但保留当前正文、推理、finishReason和协议元数据。
  - deferred保留完整toolCalls并聚合同一choice的正文/reasoning，空串不覆盖，最后非空usage补齐。
  - 无工具普通流原样通过；异常多choice/tool aggregate失败关闭。
- [ ] **Step 2: 正常完成才释放并清理**
  - concatMap保序实时输出，concatWith/defer仅在正常完成释放；doFinally清理，禁止onError/dispose释放。
  - 状态仅属于单次订阅，不新增静态字段、额外线程、block或全局队列。
- [ ] **Step 3: 统一生产装配与前缀来源**
  - AiProviderRegistry.newApi以父类同值构造参数使用ResponseBoundOpenAiApi，缓存和热生效不变。
  - correctionHistory新增前缀使用快照provider/model调用ReasoningOrigin.stamp，未知来源不伪造。
  - pom冻结说明补充wrapper的chunk合并/构造器边界。
- [ ] **Step 4: 编译审查与真实验证**
  - IDEA Reformat Java，Maven生产编译，独立关联审查后提交推送。
  - DeepSeek/公开桩验证后置reasoning、usage、错误取消、连续工具、HITL、长历史和标准OpenAI无扩展。
