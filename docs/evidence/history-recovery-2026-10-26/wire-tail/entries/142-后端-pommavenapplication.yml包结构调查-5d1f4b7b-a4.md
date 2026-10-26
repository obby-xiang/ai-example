> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-4` 的 wire 末条 assistant 文本（消息序号 m=24；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-05 15:40 → 2026-10-05 15:46（wire 事件时间窗，UTC+8）；当时模型：`Zhipu AI/glm-5.3`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：37834 B / 31545 字符 / 732 行；行指纹覆盖率（本批复算）6.7%（最高单文件 4%：`docs/evidence/V9-claude-Flyway迁移幂等核验.md`）
> - 类别：**过程核验**
> - 与既有件关系：V9-claude；报告口径覆盖率 0.04
> - 脱敏：路径 4 / 会话标识 0 / 身份 0 / 密钥 1 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
调查完成。以下是完整报告。

---

# 参考项目技术配置提取报告

**总体结论（先看这个）**：
- **两个项目都没有用 ChatClient / ChatMemory / @Tool / Advisor**。ai-example 声明了 `spring-ai-starter-model-openai` 但生产链路是**手写 RestClient 直调 DeepSeek**；ai-example-kimi-1 才是**真正用 Spring AI 1.1.8 API 的可运行参考**（注入自动配置的 `OpenAiApi` Bean + `chatCompletionStream()` Flux 流式），且**有构建产物和运行日志证实跑通**（jar 已打包、今天还在调 DeepSeek 工具调用）。
- **SpreadJS 全部是 `@grapecity/*` 作用域（不是 `@mescius/*`）**。ai-example 用 `^17.1.10`；**kimi-1 精确锁定 `17.1.5`**（与你目标版本一致）。
- **两个项目都没有 Tailwind**（连带检查了 ai-example-kimi / deepseek / deepseek-v4-pro / claude-opus-5.5 四个兄弟项目前端，也都没有）——新项目的 Tailwind 配置没有本地参考，需另建。样式均为 Sass（ai-example）或原生 CSS（kimi-1）。
- `completions-path` 只在 **kimi-1** 的 application.yml 中出现。
- **Maven 镜像坑**：ai-example 自带项目级 `maven-settings.xml`（阿里云 + 排除名单）；kimi-1 没有settings 文件（走全局，曾因华为云镜像同步滞后踩坑）。

---

## A. 后端 `<LOCAL_WORK>\ai-example\ai-service\`

### A.1 pom.xml（全文要点，`ai-service/pom.xml`）

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.14</version>
    <relativePath/>
</parent>
<groupId>com.example</groupId>
<artifactId>ai-service</artifactId>
<version>1.0.0</version>

<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.8</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

依赖清单：
- `spring-boot-starter-web` / `spring-boot-starter-data-jpa` / `spring-boot-starter-validation`
- **`org.springframework.ai:spring-ai-starter-model-openai`（显式带 `<version>${spring-ai.version}</version>`）**，pom 内注释原文：
  > Spring AI 1.1.8 官方 OpenAI Provider Starter。注意：Spring AI 1.1.x 对所有 model starter 采用了新的统一命名规范：由旧名 spring-ai-openai-spring-boot-starter 改为 **spring-ai-starter-model-openai**
- `com.h2database:h2`（runtime）、`lombok`（optional）、`jackson-databind`、`jackson-dataformat-yaml`、`jackson-datatype-jsr310`、`spring-boot-starter-test`（test）
- **没有 POI、没有 webflux、没有 maven-compiler-plugin 显式配置**（编译版本完全靠 `<java.version>21</java.version>` + parent）

```xml
<build>
    <finalName>ai-service</finalName>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </exclude>
                </excludes>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### A.2 maven-settings.xml（全文，`ai-service/maven-settings.xml`）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  项目级 Maven settings。
  目的：绕过全局镜像（如华为云 mirrorOf=*）对 Spring AI 1.1.8 的缺失拦截，
  让 spring-release / central-release 这两个 pom 中声明的 id 直接走源站。
  使用方式：mvn -s maven-settings.xml ...
-->
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" ...>
    <mirrors>
        <mirror>
            <id>aliyun-mirror</id>
            <!-- 拦截除 explicit 直连源之外的所有仓库，避免走到老的华为云（其 Spring AI 同步滞后） -->
            <mirrorOf>*,!spring-release,!spring-plugin-release,!central-release</mirrorOf>
            <name>阿里云公共仓库（Maven Central 同步更快）</name>
            <url>https://maven.aliyun.com/repository/public</url>
        </mirror>
    </mirrors>
</settings>
```

配套坑（memory/project-memory.md:66-67 原文）：
> - Spring AI 1.1.x starter renamed to spring-ai-starter-model-openai; old artifactId spring-ai-openai-spring-boot-starter invalid
> - 国内镜像 may not sync Spring AI new版本； use 阿里云 public mirror via project-level maven-settings.xml excluding spring/central interception

本地 `~/.m2/repository/org/springframework/ai/spring-ai-openai/` 下已有 **1.1.0 / 1.1.7 / 1.1.8 / 2.0.0**，依赖可解析。

### A.3 application.yml（全文，唯一一个 profile）

```yaml
server:
  port: 8081
  servlet:
    context-path: /

spring:
  application:
    name: ai-service

  datasource:
    url: jdbc:h2:file:./data/ai_config_db;DB_CLOSE_DELAY=-1;MODE=MySQL
    driver-class-name: org.h2.Driver
    username: sa
    password:
  h2:
    console:
      enabled: true
      path: /h2-console
      settings:
        web-allow-others: true
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.H2Dialect
    open-in-view: false
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: GMT+8

  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:your-deepseek-api-key-here}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-v4-flash
          temperature: 0.2
          max-tokens: 4096

# 自定义AI配置（用于自定义调用）
app:
  ai:
    base-url: https://api.deepseek.com
    chat-path: /chat/completions
    model: deepseek-v4-flash
    api-key: ${DEEPSEEK_API_KEY:your-deepseek-api-key-here}
    read-timeout-seconds: 180   # 长上下文流式生成可能 60s+ 不出 token，默认 30s 会误杀，需 120s+
    max-retries: 3              # 429/5xx/网络超时 指数退避 1s×2^n + jitter，尊重 Retry-After
  agent:
    waiting-tool-ttl-minutes: 10  # WAITING_TOOL 交互超时（分钟）

logging:
  level:
    org.springframework.ai: INFO
    com.example.ai: DEBUG
    org.hibernate.SQL: WARN
```

⚠️ 注意：`spring.ai.openai` 节点**没有配 `completions-path`**（该 starter 默认是 `/v1/chat/completions`）——因为 ai-example 的 AI 调用根本不走 Spring AI 自动配置的 ChatModel，而是走 `app.ai.*` 自定义配置 + 手写 RestClient。**`completions-path: /chat/completions` 的正确写法在 kimi-1（见 C 节）**。

### A.4 包结构与 AI 核心

```
com.example.ai
├── AiServiceApplication.java
├── config/    StartupInitializer / WebConfig（CORS：allowedOriginPatterns("*") + allowCredentials(true)）
├── controller/ AiController（/api/ai/**）/ AiProxyController（透明SSE代理）/ ConfigController / TaskController
├── dto/       AiDTO / ConfigDTO / R / TaskDTO
├── entity/    AgentState / AiChatMessage / ConfigDataRow / ConfigDefinition / Task
├── repository/ 对应 5 个 JPA Repository
├── service/   AiService / ConfigDataService / ConfigDefinitionService / DataSeedService / PlannerService / TaskService
└── tool/      FrontendTools / ScenarioDefinition / ToolDefinition / ToolDiscoveryService
resources/     application.yml / tools.yaml（13工具声明）/ scenarios.yaml（场景状态图）
```

**⚠️ ChatClient / ChatMemory / @Tool / Advisor：全部不存在。** 全项目 grep `org.springframework.ai` 仅命中 `tool/FrontendTools.java`（用 `OpenAiApi.FunctionTool` 做格式对照，生产链路标注"unused"）。memory 文件明确记录：
> Backend AI service uses RestClient to directly connect to DeepSeek API instead of Spring AI ChatModel

**AiService.java 核心写法**（手写 Agent Loop，非流式 JSON 请求-响应）：

```java
// RestClient + 显式超时（AiService.java:102-119）
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(10_000);
factory.setReadTimeout(readTimeoutSeconds * 1000);   // 180s
cachedClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();

// 请求体构造（AiService.java:868-895）
body.put("model", model);
body.put("temperature", temperature);
body.put("max_tokens", maxTokens);
body.put("messages", messages);
body.put("thinking", Map.of("type", "disabled"));  // 关闭 DeepSeek 思考模式，根治 400
// 动态工具发现：按 scenario+step 过滤工具集
tools = toolDiscoveryService.listAvailableTools(scenario, step);  // progressive disclosure
body.put("tools", tools);
body.put("tool_choice", "auto");

// 调用（doCallDeepSeekApi）
client().post().uri(chatPath).header("Authorization", "Bearer " + apiKey)
    .contentType(MediaType.APPLICATION_JSON).body(reqJson).retrieve().body(String.class);
```

**工具调用实现**（按请求动态注入）：
- 工具定义集中在 `resources/tools.yaml`（声明式：name/description/kind/autoExec/needConfirm/requireDoubleConfirm/scenarios/steps/parameters JSON Schema），由 `ToolDiscoveryService`（`jackson-dataformat-yaml` + `@PostConstruct` 加载）按 `scenario+step` 过滤，输出**原生 OpenAI 协议 Map**（`{type:function, function:{name,description,parameters}}`）直接放进请求体 `tools` 字段。
- **后端工具**（`list_config_defs`/`get_config_def`/`get_workspace_state`）loop 内同步执行后回填 tool message；**前端工具** loop 暂停 → `AgentState`（H2 表，`WAITING_TOOL`，resumeToken `rst-<uuid24>`，TTL 10 分钟）→ 返回 `{toolCalls, resumeToken, done:false}` → 前端执行 → `POST /api/ai/tool-result` 回灌恢复。
- 关键协议修复（AiService.java:386-391 注释原文）：
  > OpenAI 协议要求： assistant.tool_calls 中每个 tool_call_id 必须有对应 tool message 跟随。串行执行只处理第一个，故 assistant 只放这一个，避免"N 个 tool_calls 配 1 个 tool message"触发 400 insufficient tool messages

**会话管理**：
- 历史消息持久化到 H2（`AiChatMessage` 实体，按 taskId/sessionId 查询，构造上下文时**截取最近 20 条**、单条 3000 字符截断）——**不是** ChatMemory/MessageWindowChatMemory，是自研 JPA 方案。
- 暂停现场持久化到 H2（`AgentState`：messagesJson + pendingToolCallsJson + scenario/step + dataVersion + expiresAt）。
- `MAX_LOOP_ITERATIONS = 8`；协作式取消（`cancelledSessions` Set + `/api/ai/cancel`）；`/api/ai/pending` 供刷新恢复。

**AI 聊天接口**：`POST /api/ai/chat` 返回 `R<AiDTO.ChatResp>`（**同步 JSON，不是 SSE/Flux**）。ai-example 中唯一的流式代码是 `AiProxyController`（给范式2 ai-ui-vercel 用的透明字节流代理）：

```java
// AiProxyController.java 核心片段：JDK HttpClient + StreamingResponseBody
HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
HttpRequest upstream = HttpRequest.newBuilder(URI.create(target))
        .timeout(Duration.ofSeconds(readTimeoutSeconds))
        .header("Authorization", "Bearer " + apiKey)
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
HttpResponse<InputStream> upstreamResp = client.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
return out -> {
    byte[] buf = new byte[4096];
    int n;
    while ((n = is.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
};
```

### A.5 POI / 文件上传下载
**后端完全没有 POI、没有任何文件上传/下载接口。** README 明确：所有 xlsx 处理（模板/导入/导出）由前端 `@grapecity/spread-excelio` 在浏览器完成，"文件本体不进 LLM 上下文"。kimi-1 架构文档同样写明："后端做需引入 POI 且大文件传输重；前端做可利用 SpreadJS 原生能力"。

### A.6 测试
**没有 `src/test` 目录，零测试代码**（kimi-1 同样没有）。

### A.7 端口
`server.port: 8081`（README 注明"8080 被占用时用 8081"）。

---

## B. 前端 `<LOCAL_WORK>\ai-example\ai-ui\`

### B.1 package.json（依赖全文）

```json
{
  "name": "ai-ui",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview --port 5173"
  },
  "dependencies": {
    "vue": "^3.5.13",
    "vue-router": "^4.5.0",
    "pinia": "^2.3.0",
    "axios": "^1.7.9",
    "element-plus": "^2.9.3",
    "@element-plus/icons-vue": "^2.3.1",
    "@grapecity/spread-sheets": "^17.1.10",
    "@grapecity/spread-sheets-vue": "^17.1.10",
    "@grapecity/spread-excelio": "^17.1.10",
    "@grapecity/spread-sheets-charts": "^17.1.10",
    "@grapecity/spread-sheets-resources-zh": "^17.1.10",
    "dayjs": "^1.11.13",
    "uuid": "^11.0.3"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.2.1",
    "vite": "^6.0.7",
    "sass": "^1.83.4"
  }
}
```

**结论：SpreadJS 是 `@grapecity/*`（旧作用域），yarn.lock 实际解析为 17.1.10**（含被依赖拉入的 `@grapecity/spread-sheets-shapes@17.1.10`）。**不是 `@mescius/*`，不是 17.1.5**。另有 `ai-ui-vercel/package.json` 额外含 `ai@6.0.221`（Vercel AI SDK）、`@ai-sdk/openai@3.0.97`、`zod@3.25.76`。

### B.2 vite.config.js（全文）

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    host: '0.0.0.0',
    port: 5173,
    open: false,
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true, ws: false, timeout: 120000 },
      '/h2-console': { target: 'http://localhost:8081', changeOrigin: true }
    }
  },
  build: { sourcemap: false, chunkSizeWarningLimit: 2048 }
})
```

### B.3 registry
**没有 `.npmrc` / `.yarnrc` 文件**（ai-ui 与 kimi-1 frontend 都没有）。registry 证据来自 yarn.lock 的 resolved URL：
- ai-example：`https://registry.npmmirror.com/...`（淘宝/阿里 npm 镜像）
- kimi-1：`https://mirrors.huaweicloud.com/repository/npm/...`（华为云 npm 镜像）

即镜像是通过 yarn 全局配置（`yarn config set registry`）生效的，不在仓库内。

### B.4 入口 main.js（全文，`ai-ui/src/main.js`）

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// SpreadJS 资源
import '@grapecity/spread-sheets/styles/gc.spread.sheets.excel2013white.css'
import '@grapecity/spread-sheets-resources-zh'
import GC from '@grapecity/spread-sheets'
// 中文资源
GC.Spread.Common.CultureManager.culture('zh-cn')
// SpreadJS LicenseKey(通过 .env.local 注入 VITE_SPREADJS_KEY,不入库)
// 评估模式有水印和功能限制,正式使用需购买授权
GC.Spread.Sheets.LicenseKey = import.meta.env.VITE_SPREADJS_KEY || ''

import App from './App.vue'
import router from './router'
import './styles/main.scss'
import { installDataVersionCapture } from '@/utils/workspace-version'

const app = createApp(App)
const pinia = createPinia()
// ... pinia 自定义 localStorage 持久化插件（task.currentTaskId / ai.expanded / sessionStorage sessionId）...
installDataVersionCapture(pinia, router)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)   // 图标全量全局注册
}
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
```

装配方式：Element Plus **全量引入** + zh-cn locale + 图标全局注册；Pinia 在 router 之前 use；样式是 `src/styles/main.scss`（Sass）。**没有 Tailwind/postcss.config/tailwind.config 任何文件。**

### B.5 SpreadJS 集成方式（重点）

**初始化**（`ai-ui/src/components/SpreadSheet.vue:72-109`）——注意**没有用 `@grapecity/spread-sheets-vue` 包装组件，而是裸 GC API**：

```js
import GC from '@grapecity/spread-sheets'
import { applyColumnsToSheet } from '@/utils/excel-io'

function initSpread() {
  if (!hostRef.value) return
  spread = new GC.Spread.Sheets.Workbook(hostRef.value)
  spread.options.allowUserEditFormula = true
  spread.options.copyPasteHeaderOptions = GC.Spread.Sheets.CopyPasteHeaderOptions.allHeaders
  sheet = spread.getActiveSheet()
  sheet.suspendPaint()
  try {
    sheet.name(props.configDefinition?.name || 'Sheet1')
    applyColumnsToSheet(sheet, props.configDefinition, 'runtime')
    setRows(props.initialRows || [])
    // read 模式保护
    sheet.options.isProtected = true
    sheet.options.protectionOption = { allowInsertRows: false, allowDeleteRows: false, allowEditObjects: false }
    // 事件绑定
    sheet.bind(GC.Spread.Sheets.Events.CellChanged, changedHandler)
    sheet.bind(GC.Spread.Sheets.Events.RowChanged, changedHandler)
    sheet.bind(GC.Spread.Sheets.Events.RangeChanged, changedHandler)
  } finally { sheet.resumePaint() }
}
// 组件卸载时 spread.dispose()；通过 window.__spreadsheets[defId] 注册代理句柄供 AI 前端工具调用
```

**ExcelIO 导入导出**（`ai-ui/src/utils/excel-io.js`，430 行，与 ai-ui-vercel 同签名）：

```js
import GC from '@grapecity/spread-sheets'
import * as spreadExcel from '@grapecity/spread-excelio'

const DV = GC.Spread.Sheets.DataValidation
const CMP = GC.Spread.Sheets.ConditionalFormatting.ComparisonOperators
const AREA = GC.Spread.Sheets.SheetArea

// 导出：spread 实例 → xlsx Blob
function workbookToBlob(spread) {
  return new Promise((resolve, reject) => {
    const excelIO = new spreadExcel.IO()
    excelIO.save(JSON.stringify(spread.toJSON()), (blob) => resolve(blob), (e) => reject(e))
  })
}
// 导入：File → JSON → 离线 Workbook → 表头映射 + 类型转换 + 校验
//   excelIO.open(file, (json) => resolve(json), (e) => reject(e))
```

关键 API：`new spreadExcel.IO()`、`excelIO.save(JSON.stringify(spread.toJSON()), ok, err)`、`excelIO.open(file, ok, err)`；`DV.createListValidator / createNumberValidator / createFormulaValidator` 导出后保留为 **Excel 原生 Data Validation**（文档注明"已验证"）；长选项（join 后 >200 字符）用隐藏列 + `=$L$1:$L$N` 公式引用规避 Excel List 公式 255 字符上限；离线 Workbook 用 `position:fixed;left:-99999px` 挂隐藏 div 且**必须非零尺寸**（800x600）。

**LicenseKey**：`GC.Spread.Sheets.LicenseKey = import.meta.env.VITE_SPREADJS_KEY || ''`（`.env.local` 提供，`.gitignore` 排除 `*.local`）。未加 key 的表现（README:325 原文）：
> **SpreadJS LicenseKey**：评估模式有水印和功能限制。两范式 main.js 已通过 import.meta.env.VITE_SPREADJS_KEY 注入（.env.local 配置，不入库），正式使用需购买授权。

（kimi-1 README 措辞更宽松："缺省时仅显示水印，功能可用"。）

### B.6 AI 聊天 SSE 消费
**ai-ui（范式1）不用 SSE**——`api/ai.js` 全部是 axios `request.post('/ai/chat', payload, { signal })` 同步 JSON（signal 用于 AbortController 停止）。SSE 消费代码存在于：
1. **kimi-1** 的 `frontend/src/api/sse.js`（fetch + ReadableStream 手动解析，见 C 节原文）——**这是可直接复用的 POST-SSE 客户端**；
2. ai-example 的 **ai-ui-vercel**（范式2），通过 Vercel AI SDK 消费（`src/ai/provider.js`）：

```js
import { createOpenAI } from '@ai-sdk/openai'
const openai = createOpenAI({
  baseURL: '/api/ai/proxy',       // 指向后端透明代理，key 由后端注入
  apiKey: 'placeholder',
  compatibility: 'compatible',
  fetch: fetchWithTimeout          // AbortSignal.timeout(180s) + AbortSignal.any 合并调用方信号
})
export const deepseek = openai.chat('deepseek-v4-flash')
```

### B.7 Tailwind
**不存在**。无 `tailwind.config.js`、无 `postcss.config.js`、package.json 无 tailwindcss。ai-ui 用 `sass ^1.83.4` + `src/styles/main.scss`。**新项目若要 Tailwind 需从零配置，两个参考项目均无经验可抄。**

### B.8 构建成功迹象
- ai-ui：有 `yarn.lock`（registry.npmmirror.com），**无 dist/、无 node_modules/**（本副本未构建；`.gitignore` 排除 dist）。README 记载标准流程 `yarn install && yarn dev`。
- ai-ui-vercel：同上，有 yarn.lock。
- **kimi-1 是唯一个有完整构建+运行证据的**（见 C）。

---

## C. `<LOCAL_WORK>\ai-example-kimi-1\`（对比项目，**推荐作为新项目主参考**）

### C.1 可运行证据（强）
- `backend/target/quickstart-backend-1.0.0.jar` 已打包；`backend/data/quickstart_db.mv.db`（H2 已建库）
- `frontend/dist/`（index.html + assets）已构建
- `backend/backend-run.log`：**2026-10-05 当天**的真实运行日志，DeepSeek 工具调用链路完整（`list_config_defs → get_config_def(COUNTRY/TAX_RATE/...) → create_task → count_config_data`、`模型返回 6 个 toolCalls，仅顺序处理第一个` 等）
- `frontend/frontend-run.log`：`VITE v6.4.3 ready in 603 ms → http://localhost:5271/`

### C.2 后端 pom.xml 与 application.yml

pom 与 ai-example 的差异：parent 同为 **3.5.14**、`spring-ai-bom 1.1.8`；`spring-ai-starter-model-openai` **不写版本号（走 BOM）**；**没有** jackson-yaml（工具用 Java 代码注册而非 YAML）；无 `finalName`；lombok exclude 也没配。

**application.yml（全文）——这才是 `completions-path` 的出处**：

```yaml
server:
  port: 8090

spring:
  application:
    name: quickstart-backend
  datasource:
    url: jdbc:h2:file:./data/quickstart_db;MODE=MySQL
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  ai:
    openai:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY:***}   # ⚠️ 默认值硬编码了一个演示 key（应删除，仅留 env var）
      chat:
        completions-path: /chat/completions
        options:
          model: deepseek-flash
          parallel-tool-calls: false

app:
  job:
    item-delay-ms: 300
  ai:
    max-rounds: 8
    session-ttl-minutes: 120

logging:
  level:
    com.example.quickstart: info
```

⚠️ 安全提醒：该文件把一个真实格式的 DeepSeek key 作为 `${DEEPSEEK_API_KEY:...}` 的默认值写进了仓库。ai-example 的 memory（project-memory.md:88）记录过一次同类事故的完整补救流程（作废 key + git filter-branch 重写）。**新项目务必只留 `${DEEPSEEK_API_KEY:}` 占位。**

### C.3 真正的 Spring AI 1.1.8 API 写法（AiChatService.java，可运行已验证）

**注入自动配置的 OpenAiApi Bean**（无自定义 @Bean，starter 直接给）：

```java
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

public AiChatService(OpenAiApi openAiApi,           // ← 自动配置注入
                     AiToolRegistry toolRegistry,
                     BackendToolExecutor backendToolExecutor,
                     PromptBuilder promptBuilder,
                     AiSessionStore sessionStore,
                     @Qualifier("aiTaskExecutor") Executor aiExecutor,
                     ObjectMapper om,
                     @Value("${app.ai.max-rounds:8}") int maxRounds,
                     @Value("${spring.ai.openai.chat.options.model:deepseek-flash}") String model) { ... }
```

**流式调用**：

```java
ChatCompletionRequest request = buildRequest(messages, tools);
Flux<ChatCompletionChunk> flux = openAiApi.chatCompletionStream(request);
for (ChatCompletionChunk chunk : flux.toIterable()) {
    if (chunk.choices() == null || chunk.choices().isEmpty()) continue;  // include_usage 末帧判空
    var choice = chunk.choices().get(0);
    var delta = choice.delta();
    String text = delta.content();            // → SSE event: token
    String reasoning = delta.reasoningContent();  // → SSE event: reasoning
    if (choice.finishReason() == ChatCompletionFinishReason.TOOL_CALLS && delta.toolCalls() != null) {
        toolCalls.addAll(delta.toolCalls());  // 框架已合并流式 tool_calls 分片
    }
}
```

**ChatCompletionRequest 32 参构造**（源码级注释，直接照抄）：

```java
private ChatCompletionRequest buildRequest(List<ChatCompletionMessage> messages, List<FunctionTool> tools) {
    return new ChatCompletionRequest(
            messages,                                                       // 1  messages
            model,                                                          // 2  model
            null, null, null, null, null, null,                             // 3-8 store, metadata, frequencyPenalty, logitBias, logprobs, topLogprobs
            null, null,                                                     // 9-10 maxTokens, maxCompletionTokens（互斥，DeepSeek 默认即可）
            null,                                                           // 11 n
            null, null,                                                     // 12-13 modalities, audio
            null,                                                           // 14 presencePenalty
            null,                                                           // 15 responseFormat
            null,                                                           // 16 seed
            null,                                                           // 17 serviceTier
            null,                                                           // 18 stop
            true,                                                           // 19 stream（chatCompletionStream 断言必须为 true）
            ChatCompletionRequest.StreamOptions.INCLUDE_USAGE,              // 20 streamOptions
            null,                                                           // 21 temperature（走模型默认）
            null,                                                           // 22 topP
            tools,                                                          // 23 tools
            ChatCompletionRequest.ToolChoiceBuilder.AUTO,                   // 24 toolChoice
            false,                                                          // 25 parallelToolCalls（1.1.8 合并器单帧只支持单调用）
            null, null, null, null, null, null, null);                      // 26-32 user, reasoningEffort, webSearchOptions, verbosity, promptCacheKey, safetyIdentifier, extraBody
}
```

**ChatCompletionMessage 9 参构造**（我从本地 `spring-ai-openai-1.1.8.jar` 反编译核实了字段顺序 `(rawContent, role, name, toolCallId, toolCalls, refusal, audioOutput, annotations, reasoningContent)`）：

```java
// user 消息
new ChatCompletionMessage(req.message(), ChatCompletionMessage.Role.USER);
// tool 结果回填
new ChatCompletionMessage(resultJson, ChatCompletionMessage.Role.TOOL, pending.name(), pending.callId(), null, null, null, null, null);
// assistant + tool_calls（deepseek-flash 思考模式必须带本轮 reasoningContent，缺失会 400）
var toolCall = new ChatCompletionMessage.ToolCall(callId, "function",
        new ChatCompletionMessage.ChatCompletionFunction(fnName, fnArgs));
new ChatCompletionMessage(null, ChatCompletionMessage.Role.ASSISTANT,
        null, null, List.of(toolCall), null, null, null, outcome.reasoning());
```

**FunctionTool 两种构造**（均已从 1.1.8 jar 反编译验证存在）：
- kimi-1（4 参，parameters 为 Map）：`new OpenAiApi.FunctionTool(new OpenAiApi.FunctionTool.Function(t.description(), t.name(), t.parameters(), null))`
- ai-example（3 参，parameters 为 JSON 字符串）：`new OpenAiApi.FunctionTool(new OpenAiApi.FunctionTool.Function(name, desc, schemaJson))`

**SSE 下发**（SseEmitter + 专用线程池，`AiChatController`）：

```java
private static final long SSE_TIMEOUT_MS = 600_000L;   // SSE 超时 10 分钟

@PostMapping("/chat")
public SseEmitter chat(@Valid @RequestBody ChatRequest req) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    aiChatService.chat(req, emitter);
    return emitter;
}
// AiChatService 里：aiExecutor.execute(() -> runLoop(session, req.context(), emitter));
// emitter.send(SseEmitter.event().name(event).data(om.writeValueAsString(data)));
```

线程池（AsyncConfig）：`aiTaskExecutor` core 4 / max 8 / queue 100 / 前缀 `ai-`；另有 `jobTaskExecutor` core 2 / max 4 / queue 50。**启动类需 `@EnableScheduling`**（AiSessionStore 的 `@Scheduled(fixedDelay=300_000)` TTL 清理依赖它）。

**会话管理**：`AiSessionStore` —— `ConcurrentHashMap<sessionId, AiSession>` 内存存储（messages 为 `List<ChatCompletionMessage>`、displayMessages、pendingCall、running/cancelRequested 标志、lastAccess），TTL 120 分钟定时清理，**不持久化**（架构文档 D9：单机演示不引入 Redis）。sessionId 前端存 sessionStorage。工具按 `page + wizardStep` 过滤（`AiToolRegistry.available(page, step)` / `functionTools(page, step)`，16 工具 = 6 BACKEND + 10 FRONTEND）。

**SSE 事件契约**（docs/03-api-contract.md）：
`token {text}` / `reasoning {text}` / `tool_run {name,status}` / `tool_call {callId,name,arguments,needConfirm}` / `done {status: finished|waiting|cancelled|max_rounds}` / `error {message}`。

### C.4 前端（差异与已知问题）

package.json 关键差异：**SpreadJS 精确锁定 `17.1.5`（无 `^`），只 3 个包**（spread-sheets / spread-excelio / spread-sheets-resources-zh，**没有** spread-sheets-vue 和 charts）；多了 `jszip ^3.10.1`（zip 打包）；**没有 sass**；build 脚本是 `node scripts/build.mjs`。

vite.config：`port: 5271, strictPort: true`，proxy `/api → http://localhost:8090` timeout 120000。

main.js 与 ai-example 几乎一致（Element Plus 全量 + zhCn；SpreadJS CSS + resources-zh + culture('zh-cn')）；LicenseKey 写法略有不同：
```js
const key = import.meta.env.VITE_SPREADJS_KEY
if (key) { GC.Spread.Sheets.LicenseKey = key }
```

**SSE 消费原文**（`frontend/src/api/sse.js`，fetch + ReadableStream，可直接复用）：

```js
export async function postSSE(url, body, { signal, onEvent } = {}) {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal
  })
  if (!resp.ok) { /* ...读取 data.error... */ }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  const dispatch = (chunk) => {
    let event = 'message'
    const dataLines = []
    for (const line of chunk.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    if (!dataLines.length) return
    let data = null
    try { data = JSON.parse(dataLines.join('\n')) } catch (e) { data = { text: dataLines.join('\n') } }
    onEvent && onEvent(event, data)
  }
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const chunk = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      if (chunk.trim()) dispatch(chunk)
    }
  }
  if (buffer.trim()) dispatch(buffer)
}
```

消费侧（stores/ai.js）：`_consumeStream()` 里 `postSSE(url, body, { signal: currentAbort.signal, onEvent })` 逐事件更新消息；`needConfirm=false` 的 toolCall 自动执行并回灌续流（`autoExecutedCallIds` Set 去重防死循环）；`stop()` = `currentAbort.abort()` + `aiApi.cancel(sessionId)`。

**已知坑（docs + scripts）**：
1. **Windows 构建坑**（`frontend/scripts/build.mjs` 原文注释）：
   > 本机杀毒软件会锁定 esbuild 写在系统 %TEMP% 下的临时文件导致构建失败，这里把 TMP/TEMP 指到项目内目录再调 vite build。

   做法：`process.env.TMP = process.env.TEMP = process.env.TMPDIR = <project>/.tmp-build` 后 `await build()`。`.gitignore` 排除 `.tmp-build`、`*.local`。
2. **DeepSeek 思考模式**：架构文档 D1 风险表写"回填 assistant 消息时 reasoning_content 必须为 null，否则 400"，但 AiChatService 代码与类注释是**携带本轮 reasoningContent（截断 8000 字符）**（"deepseek-flash 为思考模式，回填 assistant 消息必须携带本轮 reasoningContent（缺失会 400，与 deepseek-chat/reasoner 的旧规则相反）"）。**以代码为准：回放 reasoning，截断到 8000**。ai-example 的做法则是请求体注入 `thinking={"type":"disabled"}` 从根上关闭（适合不需要思维链的场景）。
3. `parallel-tool-calls: false` + 每轮只处理第一个 toolCall（运行日志显示 DeepSeek 经常一次返回 2~6 个 toolCalls，串行策略实测必要）。
4. 模型名是 `deepseek-flash`（kimi-1）vs `deepseek-v4-flash`（ai-example）。
5. README 文档索引引用了 `docs/05-verification.md`，但 docs 目录只有 01~04（链接失效）。
6. kimi-1 **没有 maven-settings.xml**（靠本机全局 Maven 配置拉到了 1.1.8，本地 .m2 已有缓存）；新项目建议带上 ai-example 的 settings 文件保险。
7. CORS：`allowedOriginPatterns("*") + allowCredentials(false)`（ai-example 是 `allowCredentials(true)`，两者都配了 `/**` 全放行——开发期 dev proxy 下其实非必需）。

### C.5 两项目核心差异总表

| 维度 | ai-example/ai-service | ai-example-kimi-1/backend |
|---|---|---|
| Spring AI 用法 | 仅引用 `OpenAiApi.FunctionTool` DTO；生产走手写 RestClient | **注入 OpenAiApi Bean + chatCompletionStream(Flux)** |
| 流式 | 无（同步 JSON）；仅透明字节代理 | **SseEmitter SSE（token/reasoning/tool_call/...）** |
| completions-path | 无（自定义 `app.ai.chat-path`） | **`/chat/completions`** |
| 工具定义 | tools.yaml + scenarios.yaml（声明式） | AiToolRegistry Java 代码注册 |
| 会话/记忆 | H2 持久化（AiChatMessage + AgentState resumeToken） | 内存 ConcurrentHashMap + TTL 120min |
| 思考模式处理 | 请求体注入 `thinking:disabled` | 回放 reasoningContent（截断 8000） |
| 端口 | 8081 | 8090 |
| 构建证据 | 无产物（仅 yarn.lock） | **jar + dist + 当日运行日志** |

---

## D. `<LOCAL_WORK>\ai-example\README.md` + `scripts\`

**端口约定**：ai-service **8081**；ai-ui dev **5173**；ai-ui-vercel dev **5174**（kimi-1：后端 8090 / 前端 5271 strictPort）。

**启动命令**（README 原文）：

```bash
# 后端
cd ai-service
$env:DEEPSEEK_API_KEY = "sk-your-api-key"        # PowerShell
mvn clean package -DskipTests -s maven-settings.xml
java -jar target/ai-service.jar

# 前端·范式1
cd ai-ui
yarn install
yarn dev            # http://localhost:5173

# 前端·范式2（需后端在 8081 运行）
cd ai-ui-vercel
yarn install
yarn dev            # http://localhost:5174
```

`scripts/` 目录只有一个文件：`scripts/githooks/pre-commit`（敏感信息扫描，覆盖 `sk-`/`ghp_`/`AKIA`/`AIza`/`xox`/`eyJ` 模式，占位符白名单）。启用方式：`git config core.hooksPath scripts/githooks`。**建议新项目照搬**——memory 里记录过一次真实密钥泄露事故及补救。

---

## 给新项目的落地建议（基于以上证据）

1. **后端骨架抄 kimi-1**：Spring Boot 3.5.14 parent + `spring-ai-bom 1.1.8` + `spring-ai-starter-model-openai`（版本走 BOM），yml 配 `spring.ai.openai.{base-url, api-key(env), chat.completions-path:/chat/completions, chat.options.{model, parallel-tool-calls:false}}`；AI 层用「注入 OpenAiApi + 手写薄 Agent Loop + SseEmitter + 专用线程池 + @EnableScheduling」。注意 pom 里不要 webflux 也能用 `Flux.toIterable()`（reactor 由 spring-ai 传递引入）。
2. **若要 ChatClient/ChatMemory 高层 API**：两个参考项目都没有可抄的代码，属于未验证路径；低层 `OpenAiApi` 方案是已验证可行的。
3. **Maven**：带上 ai-example 的 `maven-settings.xml`（阿里云 + `*,!spring-release,!spring-plugin-release,!central-release` 排除名单），用 `mvn -s maven-settings.xml`。
4. **前端**：SpreadJS 用 `@grapecity/*`（17.1.5 精确锁定，3~5 个包按需），裸 `new GC.Spread.Sheets.Workbook(el)` 初始化（不依赖 vue 包装组件），CSS 引 `gc.spread.sheets.excel2013white.css`，LicenseKey 走 `VITE_SPREADJS_KEY`；SSE 消费直接复制 kimi-1 的 `api/sse.js`；npm registry 用 npmmirror（无 .npmrc，靠 yarn 全局配置）。
5. **Tailwind 无参考**，需自行新增（两个项目 + 四个兄弟项目均未使用）。
6. **Windows 坑**：构建包装脚本把 TMP 指到项目内目录（防杀毒锁 esbuild 临时文件）；maven clean 前先杀占用 jar 的 java 进程。
