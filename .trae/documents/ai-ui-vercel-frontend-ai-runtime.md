# ai-ui-vercel:前端 AI Runtime 范式实现

## Context(为什么做这件事)

现有 `ai-example` 架构是「**重后端**」:`ai-service` 在后端跑 agent loop(while 循环调 DeepSeek 直到无 tool\_calls)、管任务持久化(H2)、场景状态图、Plan-Execute;前端只做工具暂停-恢复(`resumeToken` 回灌)。

用户要新增一个**对照范式项目** `ai-ui-vercel`:把 AI runtime 前移到浏览器——用 **Vercel AI SDK** **`ai@6.0.221`** 的 `streamText` 在前端跑 agent loop、执行工具、消费流式;**后端只做透明 SSE 代理**注入 DeepSeek api-key(防泄露)。约束:前端**只用** **`ai`** **的非界面能力**(`streamText`/`createOpenAI`/`tool`/`convertToModelMessages`),**不用** **`@ai-sdk/vue`** **的** **`useChat`** 等 UI hook;界面全部用 Vue 手写。范围:**完整复刻现有应用**(仪表盘/向导/SpreadJS/场景状态图/11 个工具/表单交互/Plan-Execute)。

## 架构总览

```
浏览器 (ai-ui-vercel, Vue3+Vite, port 5174)
  └─ streamText({ model, system, messages, tools, maxSteps })   ← AI runtime 在前端
       ├─ model = createOpenAI({ baseURL:'/api/ai/proxy', apiKey:'placeholder' }).chat('deepseek-v4-flash')
       ├─ tools[*].execute  全部在浏览器内执行
       │    ├─ list_config_defs / get_config_def  → 读 src/mock/configDefs.js
       │    ├─ navigate_step → taskStore.gotoStep + router.push
       │    ├─ table_* / select_definitions → 操作 SpreadJS 实例
       │    ├─ run_flow → 双确认门 + 模拟执行
       │    └─ collect_user_input → 渲染 SchemaFormRenderer,await 用户提交(暂停在 execute 内)
       └─ fullStream 分片 → AiPanel 增量渲染文本 + 工具卡片
                │
                ▼  POST /api/ai/proxy/chat/completions  (stream:true, SSE)
后端 ai-service (port 8081, 复用现有服务)
  └─ POST /api/ai/proxy/**  ← 新增透明代理控制器
       └─ 注入 Authorization: Bearer ${app.ai.api-key}
       └─ 转发 https://api.deepseek.com/chat/completions
       └─ SSE 字节透传回前端(StreamingResponseBody + JDK HttpClient)
```

**与旧架构的本质差异**:agent loop、工具执行、暂停-恢复全部从后端移到前端;`resumeToken` 机制消失(暂停发生在 tool 的 `execute` Promise 内);任务持久化从 H2 移到 localStorage;配置定义从后端查询移到前端 mock。

## 关键版本(已通过 npm registry 核实)

| 包                                                               | 版本        | 依据                                                                                                       |
| --------------------------------------------------------------- | --------- | -------------------------------------------------------------------------------------------------------- |
| `ai`                                                            | `6.0.221` | 用户指定;registry 确认存在,依赖 `@ai-sdk/provider@3.0.13`+`provider-utils@4.0.37`                                  |
| `@ai-sdk/openai`                                                | `3.0.97`  | npm dist-tag **`ai-v6`** 指向此版;依赖 `provider@3.0.15`+`utils@4.0.46`,与 `ai@6.0.221` 同主版本,npm 去重为单一副本,无接口不匹配 |
| `zod`                                                           | `3.25.76` | `ai` 的 peer 依赖,用于 tool 参数 schema                                                                         |
| `vue`/`vite`/`pinia`/`element-plus`/`@grapecity/spread-sheets*` | 同 ai-ui   | 直接复用                                                                                                     |

> ⚠️ 不要装 `@ai-sdk/openai@latest`(4.0.46 依赖 provider 4.x,与 ai 6.0.221 的 provider 3.x 大版本冲突,会嵌套两份 provider 导致运行时接口不匹配)。安装命令:`yarn add ai@6.0.221 @ai-sdk/openai@3.0.97 zod@3.25.76`。

## 实施阶段

### Stage 1 — 后端流式代理(扩展现有 ai-service)

**新建** [AiProxyController.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/controller/AiProxyController.java)

* 路径 `@RequestMapping("/api/ai/proxy/**")`,通配子路径以兼容 SDK 是否带 `/v1` 前缀;`sub.startsWith("/v1")` 时剥离,再拼 `app.ai.base-url + sub`(`https://api.deepseek.com/chat/completions`)。

* 读取请求体字节,用 **JDK 21** **`java.net.http.HttpClient`**(零新依赖,`ai-service/pom.xml` 已确认无 webflux/reactor,避免引入 reactive server 冲突)+ `BodyHandlers.ofInputStream()`,注入 `Authorization: Bearer ${app.ai.api-key}`,POST 到 DeepSeek,把返回的 SSE `InputStream` 逐块写入 `StreamingResponseBody` 的 `OutputStream` 并 `flush()`,实现字节级透传。

* 响应 `Content-Type: text/event-stream`。**忽略**前端传入的占位 `Authorization`,api-key 永不离开后端。

* 复用现有 `@Value("${app.ai.api-key}")` `@Value("${app.ai.base-url}")` 配置([application.yml:44-50](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/application.yml#L44-L50))。

* CORS:前端经 Vite dev proxy 同源访问,生产若跨域再补 `@CrossOrigin`(现有工程无全局 CORS 类,先不引入)。

核心骨架:

```java
@RestController
@RequiredArgsConstructor
public class AiProxyController {
    @Value("${app.ai.base-url}") private String baseUrl;
    @Value("${app.ai.api-key}") private String apiKey;

    @RequestMapping("/api/ai/proxy/**")
    public StreamingResponseBody proxy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String sub = req.getRequestURI().substring("/api/ai/proxy".length());
        if (sub.startsWith("/v1")) sub = sub.substring(3);
        byte[] body = req.getInputStream().readAllBytes();
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        return out -> {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest h = HttpRequest.newBuilder(URI.create(baseUrl + sub))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<InputStream> r = client.send(h, HttpResponse.BodyHandlers.ofInputStream());
            InputStream is = r.body(); byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
        };
    }
}
```

**验证**:`mvn clean package` 重启后端;`curl -N -X POST http://localhost:8081/api/ai/proxy/chat/completions -H "Content-Type: application/json" -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"你好"}],"stream":true}'` 应持续输出 `data: {...}` SSE。

### Stage 2 — 脚手架 + 最小流式对话验证

**新建** [ai-ui-vercel/package.json](file:///e:/workspace/trae/hello-world/ai-example/ai-ui-vercel/package.json):依赖见上表;`dev` 端口 **5174**(避免与 ai-ui 的 5173 冲突)。

**新建** `ai-ui-vercel/vite.config.js`:`@`→`src`;dev proxy `/api/ai/proxy` 与 `/api` → `http://localhost:8081`(复用 ai-ui [vite.config.js:16-27](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/vite.config.js#L16-L27) 模式)。

**新建** `src/ai/provider.js`:

```js
import { createOpenAI } from '@ai-sdk/openai'
export const deepseek = createOpenAI({
  baseURL: '/api/ai/proxy',         // SDK → POST /api/ai/proxy/chat/completions
  apiKey: 'placeholder',            // 后端注入真实 key,这里只占位
  compatibility: 'compatible'       // OpenAI 兼容模式(DeepSeek)
}).chat('deepseek-v4-flash')
```

**最小验证页** `src/views/SmokeChat.vue`:用 `streamText({ model: deepseek, prompt })` 消费 `result.textStream` 逐字渲染,确认代理链路通。

### Stage 3 — 复用基础设施(从 ai-ui 拷贝/适配)

直接拷贝(几乎不改):

* [main.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/main.js)、[App.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/App.vue)(常驻右 AiPanel 布局)、[router/index.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/router/index.js)(wizard 路由 + stepToRoute)

* [components/SchemaFormRenderer.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/SchemaFormRenderer.vue)(原样复用)、[components/StepHeader.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/StepHeader.vue)(含 displaySteps 兜底)、[components/SpreadSheet.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/SpreadSheet.vue) 及表格相关组件、`src/assets`/样式、`views/DashboardView.vue`、`views/wizard/*.vue` 全部向导视图

需要**改造**(范式不同):

* `stores/task.js`:任务 CRUD 从调后端 REST 改为 **localStorage 持久化**(满足"创建即持久化、刷新可恢复"约束)

* `stores/ai.js`:**重写**(见 Stage 6)

* `utils/frontend-tool-registry.js` → 拆为 `tools/index.js`+`tools/schema.js`+`tools/registry.js`(见 Stage 5)

* `components/AiPanel.vue`:**重写**(见 Stage 6)

* `api/`:删除 `ai.js`/`request.js`(不再调后端 AI 接口);若向导视图无其他 REST 调用则整个 `api/` 可移除

### Stage 4 — 前端数据层(替代后端)

* `src/mock/configDefs.js`:配置项定义 mock 数据(后端无此接口,工具 `list_config_defs`/`get_config_def` 的 `execute` 读它)。

* `src/stores/task.js`:Pinia + localStorage;`createTask`/`gotoStep`/`setCurrentStep` 全本地;`SCENARIO_STEPS` 常量从 [scenarios.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/scenarios.yaml) 平移为 JS 常量;保留跨场景跳步校验(经验沉淀)。

* `src/stores/workspace.js`:SpreadJS 实例引用 + 当前 scenario/step + selectedDefs + 表格数据(供工具 execute 读写)。

### Stage 5 — 工具层(前端 AI runtime 核心)

**`src/tools/schema.js`**:用 `zod` 为 11 个工具定义参数 schema(对齐 [tools.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/tools.yaml) 与 [FrontendTools.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/FrontendTools.java))。

**`src/tools/index.js`**:用 `ai` 包的 `tool()` 构造工具对象,**每个工具带** **`execute`**:

```js
import { tool } from 'ai'
import { z } from 'zod'

export const tools = {
  list_config_defs: tool({
    description: '列出可用配置项定义',
    parameters: z.object({ keyword: z.string().optional() }),
    execute: async ({ keyword }) => listConfigDefs(keyword) // 读 mock
  }),
  get_config_def: tool({
    description: '获取单个配置项定义详情',
    parameters: z.object({ code: z.string() }),
    execute: async ({ code }) => getConfigDef(code)
  }),
  navigate_step: tool({
    description: '跳转到指定工作区步骤',
    parameters: z.object({ step: z.string(), scenario: z.string().optional() }),
    execute: async ({ step, scenario }) => {
      const r = await navigateStep(step, scenario) // taskStore.gotoStep + router.push,含跨场景跳步防护
      return r
    }
  }),
  collect_user_input: tool({
    description: '向用户收集结构化输入(表单)。fields 数组定义字段',
    parameters: z.object({
      formTitle: z.string(), submitLabel: z.string().optional(),
      fields: z.array(z.object({ key:z.string(), label:z.string(),
        type:z.enum(['text','textarea','number','boolean','single_select','multi_select','button_group','date']),
        required:z.boolean().optional(), default:z.any().optional(), placeholder:z.string().optional(),
        description:z.string().optional(),
        options:z.array(z.object({label:z.string(),value:z.any()})).optional(),
        min:z.number().optional(), max:z.number().optional(), pattern:z.string().optional() }).optional())
    }),
    execute: async (args) => await waitForUserForm(args) // 关键:暂停渲染表单,等用户提交
  }),
  // table_batch_set_field / table_delete_rows / table_replace_values / select_definitions
  //   execute → 操作 workspace 里的 SpreadJS 实例
  // run_flow  execute → 双确认门(ToolCard 确认 + ElMessageBox 二次确认)再模拟执行
  // confirm_complete / get_workspace_state
}
```

**暂停-恢复范式的迁移**(核心设计点):旧架构用 `resumeToken` 在后端暂停 loop;新架构把暂停**移进 execute 的 Promise**:execute 设置一个 Vue 响应式 `pendingTool`(`{args, resolve, reject}`)→ AiPanel 据 `pendingTool` 渲染交互卡片(确认按钮 / SchemaFormRenderer)→ 用户操作后 `resolve(结果)` → execute 返回 → SDK 把结果回灌模型继续 loop(下一 step)。`waitForUserForm` / `waitForConfirm` 是返回 `new Promise` 并注册到 `pendingTool` 的工具函数。

**`src/tools/registry.js`**:UI 元数据(对齐 [FrontendTools.summarizeImpact](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/FrontendTools.java) 与 [AiService.toolTitle](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/AiService.java#L687-L699)):`title`/`impact`/`autoExec`/`needConfirm`/`requireDoubleConfirm`,供 ToolCard 决定渲染形态(经验:run\_flow 双确认、collect\_user\_input 表单卡、autoExec 自动)。

### Stage 6 — AiPanel 重写(streamText 驱动)

**重写** `src/components/AiPanel.vue` + `src/stores/ai.js`:

* `ai.js`:`sendMessage(text)` 调 `streamText({ model: deepseek, system, messages: toModelMessages(history), tools, maxSteps: 10, onFinish })`。

* 消费 `result.fullStream`(`for await`):按分片类型更新消息——`text-delta` 增量拼到当前 assistant 文本;`tool-call` 追加工具卡片(状态 pending);`tool-result` 更新卡片状态为 succeeded(注意:streamText 自动执行带 execute 的工具,fullStream 会同时给出 tool-call 与 tool-result)。

* 工具交互:当某工具 execute 进入暂停(设置了 `pendingTool`)时,AiPanel 渲染对应卡片(CONFIRM 按钮 / SchemaFormRenderer 表单),用户操作后 resolve,loop 自动续行。

* 去除旧 `resumeToken`/`submitToolResult`/`/api/ai/tool-result` 全部分支;`mode` 字段保留用于 UI(PLAN/EXECUTE/CONFIRM/INPUT/DONE)但由前端流状态推导。

* 复用 [AiPanel.vue:274-321](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/AiPanel.vue#L274-L321) 的 `executeAndResume` 中的「取消保留 cancelled 状态」「ElMessage 错误提示」等经验。

### Stage 7 — 向导视图 + Plan-Execute

* 拷贝的 `views/wizard/*.vue` 改为从新 `taskStore`/`workspaceStore` 取数据(原调后端 REST 处改本地)。

* StepHeader 进度条复用(含 scenario=null 兜底,经验沉淀)。

* Plan-Execute:轻量实现——`generatePlan` 用 `generateObject({ model: deepseek, schema: PlanSchema, messages })` 生成 plan(失败兜底用 SCENARIO\_STEPS 直接构造,对齐 [PlannerService](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/PlannerService.java) 兜底逻辑),渲染 Plan 卡片,用户一次确认后把 plan 注入 system 进入主 `streamText` loop。

### Stage 8 — 端到端验证

1. `mvn clean package` 重启 ai-service(端口 8081)。
2. `cd ai-ui-vercel && yarn && yarn dev`(端口 5174)。
3. 用 **TRAE-browseruse** 打开 `http://localhost:5174`,验证:

   * 仪表盘创建任务(刷新页面任务仍在 → localStorage 持久化 OK)。

   * 进入向导,顶部进度条随步骤推进(验证 Stage 3 StepHeader 复用 + 经验修复)。

   * 右 AiPanel 发消息 → 流式逐字渲染(代理 + streamText 链路 OK)。

   * 触发 `collect_user_input` → 渲染表单(复用 SchemaFormRenderer),填写提交 → AI 续行(暂停-恢复 in execute OK)。

   * 触发 `run_flow` → 双确认门(经验)。

   * 触发 `list_config_defs` → AI 读 mock 返回(前端数据层 OK)。

   * 浏览器 DevTools Network 确认请求只打到 `/api/ai/proxy/chat/completions`,**无 api-key 出现在前端**(防泄露 OK);后端日志确认注入了真实 key。
4. 与旧 ai-ui(5173)并排对照,确认功能对等。

## 关键文件清单

**后端(改 1 处)**:新增 `ai-service/.../controller/AiProxyController.java`(Stage 1)。

**前端(新建 ai-ui-vercel,关键文件)**:

* `package.json` / `vite.config.js` / `index.html`(Stage 2)

* `src/ai/provider.js`(Stage 2)、`src/ai/system-prompt.js`(对齐 [AiService.buildRawMessages](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/AiService.java#L780) 的精简 system,描述能力不注入全量数据)

* `src/tools/{schema.js, index.js, registry.js}`(Stage 5)

* `src/stores/{ai.js, task.js, workspace.js}`(Stage 4/6)

* `src/mock/configDefs.js`(Stage 4)

* `src/components/AiPanel.vue`(重写)、`src/components/SchemaFormRenderer.vue`(拷贝)、`src/components/StepHeader.vue`(拷贝)、`src/components/SpreadSheet.vue`(拷贝)

* `src/views/wizard/*.vue`(拷贝+适配)、`src/App.vue`、`src/router/index.js`、`src/main.js`

## 风险与对策

* **SDK 版本错配**:严格锁 `ai@6.0.221`+`@ai-sdk/openai@3.0.97`,不装 latest(已核实 provider 主版本一致)。

* **SSE 透传阻塞**:用 JDK `HttpClient.send`+`StreamingResponseBody`(独立线程),`ofInputStream` 流式读;若实测有缓冲,改 `sendAsync`+`BodyHandlers.ofPublisher` 反应式。

* **streamText 自动执行 vs 确认门**:确认逻辑放进 execute Promise(不依赖 SDK 手动 tool-result 提交),`maxSteps` 设足够大(10)容许暂停。

* **Windows 文件锁**:重启 ai-service 前先杀 8081 进程再 `mvn clean`(经验)。

