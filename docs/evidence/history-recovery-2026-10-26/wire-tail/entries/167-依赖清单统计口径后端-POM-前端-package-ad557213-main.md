> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 主会话 `main` 的 wire 末条 assistant 文本（消息序号 m=47；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-25 11:02 → 2026-10-25 11:14（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：9246 B / 7237 字符 / 110 行；行指纹覆盖率（本批复算）5.1%（最高单文件 5%：`docs/research/调研-无向量知识问答业界惯例-2026-10-21.md`）
> - 类别：**过程核验**
> - 与既有件关系：提交报告-第12期；报告口径覆盖率 0.05
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
说明我的统计口径：只列 `backend/pom.xml` 与 `frontend/package.json` 里**直接声明**的项；其中显示为“实际解析”的版本，是我从构建产物 `backend/target/config-mgr.jar` 里的 `BOOT-INF/lib` 目录和 `frontend/node_modules` 中的实际安装版本核对出来的，传递依赖没有计入。

---

## 一、后端（Maven，Java 21）

### 1. 父 POM 与版本事实来源

| 项 | 版本 | 说明 |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-parent` | **3.5.14** | 父 POM；下面所有未显式写版本的依赖都由它管理 |
| `spring-ai-bom`（`dependencyManagement` 导入） | **1.1.7** | 只固定 Spring AI 系列依赖的版本；并且 `maven-enforcer-plugin` 把 `spring-ai.version` 的取值固定为 `1.1.7`（`pom.xml` 的 `enforce-reviewed-spring-ai-version`） |

### 2. 直接依赖（编译期与运行期）

| 组件 | 坐标 | 声明版本 | 实际解析 | 用途 | 官方链接 |
|---|---|---|---|---|---|
| Spring Boot Web | `spring-boot-starter-web` | 继承 | 3.5.14 | REST + 内嵌 Tomcat 10.1.54 | https://spring.io/projects/spring-boot |
| Spring Data JPA | `spring-boot-starter-data-jpa` | 继承 | 3.5.14（Hibernate 6.6.49） | 持久层 | https://spring.io/projects/spring-data-jpa |
| Bean Validation | `spring-boot-starter-validation` | 继承 | 3.5.14（Hibernate Validator 8.0.3） | 参数校验（**当前代码里 0 处使用**） | https://hibernate.org/validator/ |
| Spring Data Redis | `spring-boot-starter-data-redis` | 继承 | 3.5.14（Lettuce 6.6.0） | 会话记忆、确认门、分布式锁 | https://spring.io/projects/spring-data-redis |
| H2 Database | `com.h2database:h2` | 继承 | **2.3.232**（runtime） | 文件库 | https://www.h2database.com/ |
| Flyway | `org.flywaydb:flyway-core` | 继承 | **11.7.2** | 库迁移 | https://documentation.red-gate.com/flyway |
| Spring AI · OpenAI 适配 | `org.springframework.ai:spring-ai-starter-model-openai` | `1.1.7` | 1.1.7 | 模型调用（DeepSeek 兼容） | https://docs.spring.io/spring-ai/reference/ |
| Apache POI OOXML | `org.apache.poi:poi-ooxml` | **显式 5.5.1** | 5.5.1 | Excel 读写 | https://poi.apache.org/ |
| Jackson Databind | `com.fasterxml.jackson.core:jackson-databind` | 继承 | **2.21.2** | JSON / `data_json` | https://github.com/FasterXML/jackson |
| Jackson JSR-310 | `jackson-datatype-jsr310` | 继承 | 2.21.2 | `java.time` 序列化 | https://github.com/FasterXML/jackson |
| Apache Lucene Core | `org.apache.lucene:lucene-core` | **显式 9.12.2** | 9.12.2 | 知识库索引/BM25 | https://lucene.apache.org/ |
| Lucene Analysis Common | `lucene-analysis-common` | 显式 9.12.2 | 9.12.2 | 分析器基座 | https://lucene.apache.org/core/9_12_2/ |
| Lucene SmartCN | `lucene-analysis-smartcn` | 显式 9.12.2 | 9.12.2 | 中文分词 | https://lucene.apache.org/core/9_12_2/ |
| Lombok | `org.projectlombok:lombok` | 继承（`optional`） | **1.18.46** | 编译期注解（打包时被 `spring-boot-maven-plugin` 排除） | https://projectlombok.org/ |

### 3. 仅测试与可选 profile

| 组件 | 版本 | 范围 | 说明 |
|---|---|---|---|
| `spring-boot-starter-test` | 3.5.14 | test | JUnit 5 / Mockito / AssertJ / MockMvc |
| `org.jacoco:org.jacoco.agent`（classifier `runtime`） | **0.8.15** | test，`-Ptestmap` | 倒排录制，默认不生效 |
| `org.jacoco:org.jacoco.core` | 0.8.15 | test，`-Ptestmap` | 同上 |
| `jacoco-maven-plugin` | 0.8.15 | `-Ptestmap` | 同上 |

### 4. 构建设置（非运行组件）

| 插件 | 版本 | 说明 |
|---|---|---|
| `maven-enforcer-plugin` | **显式 3.5.0** | 强制要求 `spring-ai.version == 1.1.7`，否则在 validate 阶段就失败 |
| `maven-resources-plugin` | Boot 管理 | 把 `docs/kb-samples` 复制到 `classes/kb-samples`（`filtering=false`） |
| `spring-boot-maven-plugin` | 3.5.14 | `finalName=config-mgr`，打包时排除 lombok |
| `maven-surefire-plugin` | Boot 管理 | 仅在 `-Ptestmap` 下修改 fork 与排序 |

> 运行时外部依赖：**Redis 7.x / Memurai**（不在 pom 中，通过 `127.0.0.1:6379` 强依赖）。

---

## 二、前端（Vue 3 + TypeScript，Node 22 + yarn）

### 1. 运行期依赖（`dependencies`）

| 组件 | 声明 | 实际安装 | 用途 | 官方链接 |
|---|---|---|---|---|
| Vue | `^3.5.13` | **3.5.43** | 框架 | https://vuejs.org/ |
| Vue Router | `^4.5.0` | **4.6.4** | 路由 | https://router.vuejs.org/ |
| Pinia | `^2.3.0` | **2.3.1** | 状态管理 | https://pinia.vuejs.org/ |
| Vue I18n | `11.4.16`（精确） | 11.4.16 | 中英双语 | https://vue-i18n.intlify.dev/ |
| Element Plus | `^2.9.3` | **2.14.7** | UI 组件库 | https://element-plus.org/ |
| Element Plus Icons | `^2.3.1` | **2.3.2** | 图标 | https://github.com/element-plus/element-plus-icons |
| Axios | `^1.7.9` | **1.20.0** | HTTP | https://axios-http.com/ |
| Day.js | `^1.11.13` | **1.11.23** | 日期（**插件未启用**） | https://day.js.org/ |
| markdown-it | `15.0.0`（精确） | 15.0.0 | Markdown 渲染 | https://github.com/markdown-it/markdown-it |
| Mermaid | `11.16.1`（精确） | 11.16.1 | 图表 | https://mermaid.js.org/ |
| highlight.js | `11.11.1`（精确） | 11.11.1 | 代码高亮（`lib/core` + 15 语言） | https://highlightjs.org/ |
| DOMPurify | `3.4.14`（精确） | 3.4.14 | HTML 消毒 | https://github.com/cure53/DOMPurify |
| SpreadJS Sheets | `17.1.5`（精确） | 17.1.5 | 在线表格引擎（**商业授权**） | https://www.grapecity.com/spreadjs |
| SpreadJS ExcelIO | `17.1.5`（精确） | 17.1.5 | xlsx 导入导出（同上授权） | https://www.grapecity.com/spreadjs |
| SpreadJS 中文资源 | `17.1.5`（精确） | 17.1.5 | 表格中文化 | https://www.grapecity.com/spreadjs |
| JSZip | `^3.10.1` | **3.10.2** | 前端打包 zip / 构造 xlsx 容器 | https://stuk.github.io/jszip/ |

### 2. 开发与构建依赖（`devDependencies`）

| 组件 | 声明 | 实际安装 | 用途 | 官方链接 |
|---|---|---|---|---|
| Vite | `^6.0.7` | **6.4.4** | 构建/dev server | https://vite.dev/ |
| `@vitejs/plugin-vue` | `^5.2.1` | **5.2.4** | Vue SFC 编译 | https://github.com/vitejs/vite-plugin-vue |
| TypeScript | `^5.7.2` | **5.9.3** | 语言 | https://www.typescriptlang.org/ |
| vue-tsc | `^2.2.0` | **2.2.12** | 类型检查 | https://github.com/vuejs/language-tools |
| Vitest | `4.1.11`（精确） | 4.1.11 | 单元测试 | https://vitest.dev/ |
| `@vue/test-utils` | `2.5.1`（精确） | 2.5.1 | 组件测试 | https://test-utils.vuejs.org/ |
| jsdom | `30.1.2`（精确） | 30.1.2 | 测试 DOM | https://github.com/jsdom/jsdom |
| Playwright Test | `1.64.1`（精确） | 1.64.1 | 浏览器测试 | https://playwright.dev/ |
| TailwindCSS | `^3.4.17` | **3.4.19** | 原子化样式 | https://tailwindcss.com/ |
| PostCSS | `^8.4.49` | **8.5.29** | CSS 处理 | https://postcss.org/ |
| Autoprefixer | `^10.4.20` | **10.6.1** | 浏览器前缀 | https://github.com/postcss/autoprefixer |
| ioredis | `^6.0.0` | 6.0.0 | 测试/桩脚本里连 Redis | https://github.com/redis/ioredis |
| `@types/node` | `^22.10.2` | **22.20.5** | Node 类型 | https://github.com/DefinitelyTyped/DefinitelyTyped |

---

## 三、几条值得注意的观察

1. **JSON Schema 校验器其实已经在运行期 classpath 里**。上一轮我建议引入 `networknt:json-schema-validator`，这次我在核对产物时发现：`spring-ai-starter-model-openai:1.1.7` 会依次引到 MCP SDK（`mcp-json-jackson2:0.18.2`），而 MCP SDK 又把 **`com.networknt:json-schema-validator:2.0.0`** 作为 compile 依赖带了进来（在 `BOOT-INF/lib` 里可以看到）。因此，要拿 `inputSchema` 做工具入参校验是**零新增依赖**的，我上一轮的结论可以再加强一格。

2. **`spring-boot-starter-validation` 是一个"引用了但没用"的依赖**：它会被打进运行包（`hibernate-validator-8.0.3`、`jakarta.validation-api-3.0.2`、`validation-api-1.1.0.Final` 都在 lib 里），但业务代码里 0 个约束注解。

3. **后端没有的东西同样能说明设计取向**：没有 Spring Security、没有 Spring Batch、没有 Quartz、没有 Resilience4j、没有 Redisson、没有 Micrometer 直依赖（`micrometer-core:1.15.11` 是 `spring-ai-commons` 带进来的）、没有 MapStruct、没有 EasyExcel。

4. **前端也没有通用的工具库**：没有 VueUse、没有 lodash、没有 ajv/zod、没有 ESLint/Prettier。其中 VueUse 和 lodash-es 其实已经在 `node_modules` 里（是 Element Plus 的间接依赖），只是没被声明为直接依赖；而 lint 工具确实完全缺席。

5. **版本策略是"两套"**：后端依靠 BOM 统一版本，并用 enforcer 固定 `spring-ai`；前端则对 SpreadJS、markdown-it、mermaid、highlight.js、DOMPurify、vue-i18n、vitest、Playwright 等使用**精确版本**（不带 `^`），其余采用 `^` 范围。Lucene 在 pom 注释里写明"公司库版本，禁止升版"。

6. `spike/` 下还有 4 个独立的 PoC 工程（`sp01ab` / `sp01c` / `sp01d` / `sp02`），它们各自带一份 `pom.xml`，但依赖与主工程同构：Spring Boot 3.5.14、Spring AI BOM、starter-web/data-redis/test，并没有引入额外组件。需要的话我可以单独列出。
