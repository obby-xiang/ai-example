# Git 钩子：敏感信息扫描 + D6 同步复核

本目录提供三条检查，敏感信息的两条共用 `scan-lib.sh`，避免规则漂移：

| 脚本 | 作用 | 退出码 |
| --- | --- | --- |
| `pre-commit` | 提交时先扫**暂存区新增/修改的行**（敏感信息），再跑 **D6 同步复核**（`scripts/ci/check-d6-sync.mjs`）；任一不过即阻断 | 0 通过 / 1 有命中或确认缺失 |
| `scan-history.sh` | 用 `git rev-list --objects --all` 遍历**全部历史 blob** 复查 | 0 无命中 / 1 有命中 / 2 参数或环境错误 |

## 1. 启用

在仓库根目录执行一次（写入本地 `.git/config`，不随提交扩散）：

```sh
git config core.hooksPath scripts/githooks
```

确认与停用：

```sh
git config --get core.hooksPath        # 期望输出 scripts/githooks
git config --unset core.hooksPath      # 停用
```

## 2. 三条规则

| 规则名 | 检测内容 |
| --- | --- |
| `SECRET` | API 密钥/令牌：通用 `s`+`k` 风格长串、GitHub PAT/OAuth/细粒度令牌、AWS `AK`+`IA`/`AS`+`IA`、Google `AI`+`za`、Slack `xo`+`x`、HuggingFace、阿里云、腾讯云、GitLab、npm、Stripe live key、JWT 三段式、`Authorization: Bearer` 长串、私钥头 `-----BEGIN ... PRIVATE KEY-----` |
| `PATH` | 本机绝对路径：盘符路径（`<DRIVE>:\...`、`<DRIVE>:/...`）与 Git Bash 挂载路径（`/<盘符>/...`，盘符 c~h） |
| `USER` | 个人用户名：动态取 `git config user.name`，并拆出按 `. _ - @ 空格` 分隔、长度 >=4 的片段；只在**正文**里查，文件路径与提交信息不参与匹配；命中片段两侧必须是非字母数字（依赖包名里恰好包含该片段时不算命中） |

扫描范围由**调用方**决定，三条规则本身不按文件类型区分：`pre-commit` 只扫 `git diff --cached -U0` 的 `+` 行（本次新增/修改内容）——**历史遗留且本次未改动的违规行不会阻断提交**；`scan-history.sh` 按 blob 扫**整份文件内容**（因此 `docs/evidence/` 附件里保存的命令行/日志原文，其历史版本同样会被翻出）。尖括号占位符在任何入口都不计（见第 3 节）。

## 3. 豁免（不会报的点）

- **尖括号占位符**：`<REPO_ROOT>`、`<MAVEN_HOME>`、`<MEMURAI_HOME>`、`<WORKSPACE>`、`<USER_HOME>` 等，匹配前整段剔除，因此代码注释与文档里的占位符不会误报；
- **密钥占位**：命中片段含 `xxx` / `your` / `placeholder` / `REDACTED` / `example` / `dummy` / `sample` / `fake` / `change-me` / `...` 视为占位，不报；
- **路径占位**：路径片段含 `xxx` / `placeholder` / `REDACTED` 或残留尖括号时不报；
- **USER 规则自动跳过**：`user.name` 未设置，或为通用值（`root`/`admin`/`user`/`test`/`ci`/`git`/`runner`/`jenkins` 等）时跳过，并在输出中提示原因；
- **USER 词边界**：用户名片段两侧必须是非字母数字才算命中（依赖包名或英文单词里恰好包含该片段时不报），因此 4 字符的低阈值不会淹没在英文正文里；
- **超长行**：单行 > 4000 字符（压缩产物等）不参与匹配；
- **二进制/图片**：`scan-history.sh` 对图片、压缩包、字体等扩展名直接的 blob 跳过，其余未知扩展名用 NUL 字节探测判定二进制后跳过，并在结果里报出跳过数量（避免字节流乱码命中路径规则）；
- 大小写：`SECRET`/`PATH` 区分大小写，`USER` 不区分。

## 4. 输出与放行

命中输出形如 `路径:行号 [规则名] 命中片段`，`SECRET` 片段做掩码（保留前 4 字符与总长度），路径与用户名原样输出以便定位。

```sh
# 默认：命中即阻断，退出码 1
git commit -m "..."

# 误报放行（降级为警告，且输出中明示放行）
AI_SECRETS_ALLOW=1 git commit -m "..."

# 等价写法（pre-commit 也接受该参数）
git commit --allow-secrets -m "..."
```

- 放行只对本次提交生效，不会修改仓库配置；
- `AI_SECRETS_ALLOW=1` 与 `--allow-secrets` **只对敏感信息扫描生效**，不影响下面的 D6 同步复核；
- `git commit --no-verify` 会跳过本仓库全部钩子（两道检查同时失效），慎用；若用了，建议随后补跑 `scan-history.sh`，并自行核对 D6 是否需更新。

## 4.1 D6 同步复核（pre-commit 第二道）

**目的**：`docs/delivery/06-新项目复用与落地指南.md`（D6）是"只迁移 AI 对话能力"的唯一说明书。暂存区出现 **AI 对话生产代码与核心契约**时，要求同一笔提交里留一条**当次**人工确认，防止"改了运行时却不复核复用指南"。

**它证明什么、不证明什么**：只证明"改 block 面的人留下了本批专用的书面确认"；**不证明** D6 内容与代码语义一致，也不校验 `reason` 是否属实。CI / 人工复核仍是必要补充。

### 4.1.1 路径分类（`scripts/ci/check-d6-sync.mjs`）

| 分类 | 覆盖 | 是否阻断 |
| --- | --- | --- |
| `block` | `backend/src/main/java/.../ai/**`、`backend/src/main/resources/ai/**`（`tools.yaml`）、`common/id/**`、`common/sse/**`、`frontend/src/components/AiPanel/**`、`stores/ai.ts`、`api/{ai,aiConfig,sse,http}.ts`、`types/{ai,sse,tools}.ts`、`utils/{ai-*,frontend-tools,context-usage,parts}.ts`、`locales/**` | **是**（必须有有效确认） |
| `warn` | 后端 `src/test` 下的 AI / `common/id` / `common/sse` 测试、前端单测与 `e2e/**`、`scripts/ci/stub-*`、`verify-e2e.ps1`、`b23l1-dual-instance.ps1`、`messages_*.properties`、`stores/locale.ts` | 否（只打印告警） |
| `doc` | `docs/**`、任何 `*.md`（含 `docs/delivery/d6-review.json`） | 否 |
| `infra` | `scripts/**`、`.github/**`、构建配置（`pom.xml`/`package.json`/`tsconfig*`/`vite.config.*`/`application*.yml` 等） | 否 |
| `other` | 其余业务文件 | 否（忽略） |

> 测试与模拟设施（桩、harness、E2E、Fake/InMemory）**只告警**：它们不属于 D6 的搬迁资产；业务文件一律忽略。改 block / warn 分类表必须同时把 `RULE_VERSION` 升一版，让旧确认失效。

### 4.1.2 确认记录：`docs/delivery/d6-review.json`

```json
{
  "version": 1,
  "reviews": [
    {
      "fingerprint": "sha256:<64 位小写十六进制>",
      "status": "synced",
      "reason": "本次改了什么、D6 是否需要更新及原因",
      "reviewedAt": "2026-10-30",
      "reviewer": "<姓名或工号>"
    }
  ]
}
```

- `status` 只有两种：`synced` = 本批**同时**更新了 D6 正文（门禁核对 D6 文件确实在暂存区）；`not-affected` = 本次改动不影响 D6 描述的迁移面，`reason` 必须是**具体**理由（去空白后 ≥8 字，且不是 `n/a` / `无` / `待补` 这类占位）。
- **确认必须本次新增**：HEAD 里已有的同指纹记录不会被复用（防止历史确认被反复套用）；
- **指纹** = `sha256(规则版本 + 排序后的「状态 blob 路径」)`，只取本次暂存的 block 文件；`d6-review.json` 自身不参与计算。改文件内容、增减 block 文件都会让旧确认立即失效——重新跑一次门禁即可拿到新指纹和可直接粘贴的模板。

### 4.1.3 退出码与处理

| 退出码 | 含义 | 处理 |
| --- | --- | --- |
| 0 | 无 block 变更，或确认有效 | 继续提交 |
| 1 | 有 block 变更但确认缺失 / 指纹不符 / `synced` 未同批改 D6 / `not-affected` 理由不具体 / 复用了历史记录 | 按输出里的模板补 `d6-review.json` 并 `git add` 同一笔提交 |
| 2 | `d6-review.json` 非法 JSON、`reviews` 结构错误、`version` 不符、非 git 仓库 | **按失败处理**：先修 JSON 再提交（坏 JSON 不会被当成"无确认"放行） |

单独跑（不动索引）：

```sh
node scripts/ci/check-d6-sync.mjs            # 读当前暂存区
node scripts/ci/check-d6-sync.mjs --root <仓库根>
node scripts/ci/check-d6-sync.mjs --help
```

单测（**逐条**跑，不要一次跑全部）：

```sh
node --test --test-name-pattern="核心 AI 文件带空 reviews 时阻断" scripts/ci/check-d6-sync.test.mjs
node --test --test-name-pattern="synced 确认缺少同批 D6 变更时阻断" scripts/ci/check-d6-sync.test.mjs
node --test --test-name-pattern="d6-review.json 非法 JSON 返回 rc2" scripts/ci/check-d6-sync.test.mjs
```

### 4.1.4 跳过与局限（必须知道）

- **没装 Node.js**：pre-commit **明确告警并跳过**第二道（输出 `敏感信息扫描=通过，D6 同步复核=跳过`），提交不会被拦——此时靠人工复核 / CI；
- **`git commit --no-verify`**：两道检查一起失效，且不留任何留痕。不要把它当作常规手段；
- **没有 CI 兜底门禁**：本批只落了 pre-commit（CI 侧未新增 job），因此在未启用 `core.hooksPath` 的克隆或图形客户端里，第二道不会执行；
- **不判断正确性**：门禁无法验证 `reason` 真实、也无法验证 D6 写对了。它是"提醒 + 留痕"，不是语义证明。

## 5. 全历史扫描

```sh
scripts/githooks/scan-history.sh              # 全量：命中路径清单 + 明细
scripts/githooks/scan-history.sh --files-only # 只列命中路径与条数
scripts/githooks/scan-history.sh --max-size 512   # 跳过 >512KB 的 blob（默认 2048KB）
```

实现要点：`git rev-list --objects --all` 枚举全部可达对象 → 按 sha 取首个可见路径 → 只保留 blob 类型且体积达标的对象 → 逐个 `git cat-file blob` 读内容跑同样三条规则。因为是 blob 粒度，**历史中被删除或改写掉的内容同样会被发现**。

输出中每条命中附该历史版本的 blob 短 sha，可用 `git cat-file blob <sha>` 复核；行号对应该 blob 内容，与当前工作树行号可能不同。命中退出码为 1（脚本自身语义不变；CI 侧按**报告制**解读，见第 6 节）。

性能：串行逐个 blob 读取，本仓库唯一 blob 约 2000 个（2026-10-08 实测：`git rev-list --objects --all` 可达对象 4172 个 —— blob 1987 / tree 1939 / commit 246；1987 个 blob 全部在默认 2048KB 体积上限内），需数分钟；`--files-only` 不改变扫描耗时，`--max-size` 调小可跳过体积较大的文档快照。

## 5.1 与 DC-08 已知冲突点（合流前必看）

- 提交时只扫**暂存区新增/修改的行**：历史遗留且本次未改动的违规行不会阻断提交，但会被 `scan-history.sh` 全部翻出来；
- 证据类附件（`docs/evidence/*.json`、`*.log`、`*.txt` 等）里若保存了命令行/日志原文，常带盘符绝对路径，属于本规则的**真命中**。这类文件一旦进入暂存区就会被阻断，处理方式二选一：按 DC-08 换成占位符后入库，或 `AI_SECRETS_ALLOW=1` 留痕放行并登记待清理；
- 若确认某条规则在本仓库属于长期误报（例如合法文档必须保留某种绝对路径），应改 `scan-lib.sh` 的规则而不是常态使用逃生口。

## 6. Windows / Git Bash 注意事项

- 钩子以 `#!/bin/sh` 运行（Git Bash 提供 sh）。脚本必须保持 **LF 行尾**，被 CRLF 改写会出现 `bad interpreter` 或 `\r` 相关报错。本目录已随附 `.gitattributes`（`* text eol=lf`）强制固化——本机系统级 `core.autocrlf=true`（Git for Windows 默认）会把没有该声明的脚本改写成 CRLF，导致钩子静默失效；
- 若在别处新增脚本，请确认 `git check-attr eol -- <路径>` 输出 `lf`；
- 可执行位已在版本库中固化：三个脚本按 `100755` 入库（实测 `git ls-files -s scripts/githooks/` → `pre-commit` / `scan-history.sh` / `scan-lib.sh` 均为 `100755`），Linux/CI 检出后即可直接执行，**无需补 `chmod +x`**。本机 `core.filemode=false`（Git for Windows 默认）只影响本机后续改动是否记录权限变化，不影响已入库的权限位；
- `core.hooksPath` 必须是**相对仓库根**的路径（本仓库为 `scripts/githooks`）。写成盘符绝对路径在换机器/换目录后必然失效，也违反 DC-08；
- 环境变量写法：Git Bash 用 `AI_SECRETS_ALLOW=1 git commit ...`；PowerShell 用 `$env:AI_SECRETS_ALLOW=1; git commit ...`（仅当前会话有效）；
- `user.name` 取的是本地配置；一台机器上有多个身份时，确认取到的是需要保护的那个用户名（可用 `git config --show-origin user.name` 核对）；
- 路径规则同时覆盖盘符写法与 Git Bash 挂载写法，因此同一台机器上两种写法都拦得住；
- IDE/图形客户端可能自动附加 `--no-verify`，不要依赖钩子作为唯一防线；CI 侧再跑一次 `scan-history.sh` 兜底——**已落地**：`.github/workflows/ci.yml` 的 `scan-history` job（`--files-only`、10 分钟上限、`fetch-depth: 0`）。**口径（裁决①，2026-10-09）：该 job 为报告制非阻断** —— 历史 blob 命中（退出码 1）照常全量输出 + 上传 artifact + job summary 标注，但不使 CI 红（唯一手段是历史改写，需用户专项授权）；仅"扫描器未完成"（退出码 2，环境/参数错误）为红。**工作树口径的阻断面不在该 job**：`pre-commit`（本机暂存区）与 CI 的 `docs` job（`check-docs.mjs` 断言 ⑤ 按 `git ls-files` 全量；断言 ① 只覆盖 4 个导航/口径入口文档）继续阻断。

## 7. 维护与自测

- 新增规则：改 `scan-lib.sh` 里的 `scan_rule_secret` / `scan_rule_path`，**所有前缀字面量继续用拼接写法**（例如把通用前缀写成两个片段相加），否则本目录脚本自身会被规则命中，全历史扫描时会自报；
- 自测三条规则（无需提交、不改动仓库真实索引：让 `GIT_INDEX_FILE` 指向临时索引后调用钩子）：

```sh
# 用临时索引自测，不改动仓库真实索引
IDX=/tmp/scan-test-index
GIT_INDEX_FILE=$IDX git read-tree HEAD
printf 'api_key: %s%s\n' 's' 'k-AbCdEf0123456789ghijkl' > /tmp/t1.txt
GIT_INDEX_FILE=$IDX git add -f /tmp/t1.txt
GIT_INDEX_FILE=$IDX sh scripts/githooks/pre-commit   # 期望：✖ 阻断，退出码 1
```

- D6 门禁自测（同样不动工作树）：用临时索引改一个 block 文件的 blob，再直接跑检查器：

```sh
IDX=/tmp/d6-test-index
GIT_INDEX_FILE=$IDX git read-tree HEAD
b=$(printf 'class Probe {}\n' | git hash-object -w --stdin)
GIT_INDEX_FILE=$IDX git update-index --cacheinfo 100644,$b,backend/src/main/java/com/example/configmgr/ai/web/AiController.java
GIT_INDEX_FILE=$IDX node scripts/ci/check-d6-sync.mjs   # 期望：✖ 未随本次提交更新确认，rc 1；输出里有新指纹
```

  按输出模板把记录写进 `docs/delivery/d6-review.json` 并用 `git update-index --add --cacheinfo` 放进同一临时索引，再跑一次期望 rc 0；把该 JSON 破坏成非法 JSON 期望 rc 2；
- 改 D6 门禁的分类表（`BLOCK_RULES` / `WARN_RULES`）或指纹算法：**必须同时升 `scripts/ci/check-d6-sync.mjs` 的 `RULE_VERSION`**（否则旧确认不会失效），并**逐条**跑 `scripts/ci/check-d6-sync.test.mjs` 的用例；
- 清理：删除临时索引与测试文件即可，仓库索引与工作树不受影响。
