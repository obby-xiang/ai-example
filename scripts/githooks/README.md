# Git 钩子：敏感信息扫描 + 统一开发规范审查

`pre-commit` 提供两道阻断检查，另以 `scan-history.sh` 复查历史；敏感信息的两个入口共用 `scan-lib.sh`，统一规范使用 `check-d6-sync.mjs --standards`。同一脚本保留的默认 D6 模式仅作历史工具，不由 pre-commit 或 CI 调用：

| 脚本 | 作用 | 退出码 |
| --- | --- | --- |
| `pre-commit` | 依次扫描**暂存区新增/修改的行**（敏感信息）、跑**统一开发规范审查**；任一不过即阻断 | 0 通过 / 1 有命中、确认缺失或环境失败 |
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

命中输出形如 `路径:行号 [规则标签] 命中片段`，规则标签为 `SECRET suspected key/token` / `PATH local absolute path` / `USER personal user name`；`SECRET` 片段做掩码（保留前 4 字符与总长度），其余命中片段原样输出以便定位。

```sh
# 默认：命中即阻断，退出码 1
git commit -m "..."

# 误报放行（降级为警告，且输出中明示放行）
AI_SECRETS_ALLOW=1 git commit -m "..."

# 等价写法（pre-commit 也接受该参数）
git commit --allow-secrets -m "..."
```

- 放行只对本次提交生效，不会修改仓库配置；
- `AI_SECRETS_ALLOW=1` 与 `--allow-secrets` **只对敏感信息扫描生效**，不影响统一开发规范审查；
- 不得绕过本仓库钩子；两道检查缺一即不能形成合规提交。

## 4.1 D6 历史资料（非提交门禁）

`docs/delivery/06-新项目复用与落地指南.md`（D6）及 `docs/delivery/d6-review.json` 仅作历史参考，正文和已有记录保留。它们不是现行提交或 CI 门禁，不再要求更新 D6 或新增 D6 审查记录。

`check-d6-sync.mjs` 的默认 D6 模式仅保留为历史工具，pre-commit 和 CI 均不调用；不运行该模式不会导致提交失败。统一规范审查继续使用同一脚本的 `--standards` 模式。

## 4.2 统一开发规范审查（pre-commit 第二道，强制）

规范正文见 `docs/delivery/development-standards.md`，记录见 `docs/delivery/development-standards-review.json`。它覆盖当前暂存区的**全部**生产、测试、压测、脚本、CI 和文档变更，路径分类和文档白名单按统一规范自身规则判断。

- 每条记录必须有 `fingerprint`、`status`、具体 `reason`、带时区的 `reviewedAt`、`reviewer`、`specChanges`、逐文件 `files` 和七域 `domains`。`specChanges` 每项为 `{change, reason, impact}`，三个字段都必填且具体。
- standards 指纹版本为 `development-standards/v2`：排序后的 JSON 元组 `[status, mode, fromPath, path, blob]`，Git `-z` 原始路径，禁用 rename detection。ledger 排除全链路使用原始 `String` 严格相等，文档白名单不把反斜杠当目录分隔。
- 父 ledger 的 `reviews` 必须是当前 ledger 的严格前缀，历史不得删改或重排。父 ledger 中已有 fingerprint 不得再次追加，修改其他字段也不能复用。
- 当前和父 ledger 的 `reviews` 缺失或非数组均返回 `rc=2`。父 ledger 只有经 Git 树确认不存在才视为空历史；pre-commit 的 unborn HEAD 单独视为空父历史，其他 Git/JSON/版本错误失败关闭。
- `status=reviewed` 适用于代码或混合变更；符合独立白名单的纯文档可用 `not-affected`。docs 下 JS/JSON/HTML 不自动算文档，原 D6 block 路径内纯文本文档可按白名单处理。
- 所有理由拒绝空值、整体尖括号占位、已知模板原文、泛化子句及其连接组合；包含文件、hunk 或具体技术对象的说明不按组合规则误伤。`checked` 只表示人工逐 hunk 审查，不表示测试、真实接口或真实 Redis 已通过；结构校验不证明语义真实。

```sh
# 当前暂存区；pre-commit 自动执行同一命令
node scripts/ci/check-d6-sync.mjs --standards

# CI/本地范围复核：验证所有引入提交及其父记录
node scripts/ci/check-d6-sync.mjs --standards --base <base> --head <head>
# 只复核一个提交
node scripts/ci/check-d6-sync.mjs --standards --head <head>
```

CI 在 `.github/workflows/ci.yml` 的 `docs` job 中执行范围模式：push 使用 `before..head` 并严格要求 before 是 head 祖先；PR 先执行 `git merge-base PR_BASE_SHA PR_HEAD_SHA` 求实际分叉点再检查，checkout 必须 `fetch-depth: 0`。全零 before 和异常空范围失败关闭，仅同一 commit 允许空范围。

两父 merge 使用 `git merge-tree --write-tree parent1 parent2` 计算自动合并预期 tree；merge tree 等于任一父 tree 仍可豁免，仅 merge-tree 状态 0 且 merge tree 等于预期 tree 时才也可豁免，状态 1 必须追加 merge 自身记录。非法 tree 输出、其他退出状态和 octopus 继续失败关闭，所有引入提交仍逐笔验证。需要 merge 记录时以第一父 diff 和 ledger 为基线，不要求修改分支保护。

| 退出码 | 含义 | 处理 |
| --- | --- | --- |
| 0 | 无变更、合法空范围，或所有需记录的提交均有有效追加记录 | 继续提交/CI |
| 1 | 记录未同批暂存、指纹过期、复用历史、非 append-only、字段/文件/规范域缺失或理由不具体 | 逐 hunk 复审并追加完整记录 |
| 2 | 当前或父 JSON、版本、参数、祖先关系、仓库或提交读取错误 | 失败关闭，先修输入或环境 |

第二道没有敏感信息逃生口；缺少 Node 或检查脚本同样阻断，不得绕过。门禁只保证书面记录的结构、指纹、新鲜度和 append-only，不替代独立语义审查和真实运行验证。

本轮新增的 merge-tree、octopus、PR merge-base、append、组合套话、原始路径、白名单、父读取、reviews 结构和 unborn HEAD 场景只做了独立静态复核，全部 `NOT_RUN`；未修改测试代码，未执行测试、构建、git、pre-commit 或 CI。

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
- IDE/图形客户端可能绕过本地钩子，不要依赖钩子作为唯一防线；CI 侧再跑一次 `scan-history.sh` 兜底——**已落地**：`.github/workflows/ci.yml` 的 `scan-history` job（`--files-only`、10 分钟上限、`fetch-depth: 0`）。**口径（裁决①，2026-10-09）：该 job 为报告制非阻断** —— 历史 blob 命中（退出码 1）照常全量输出 + 上传 artifact + job summary 标注，但不使 CI 红（唯一手段是历史改写，需用户专项授权）；仅"扫描器未完成"（退出码 2，环境/参数错误）为红。**工作树口径的阻断面不在该 job**：`pre-commit`（本机暂存区）与 CI 的 `docs` job（`check-docs.mjs` 断言 ⑤ 按 `git ls-files` 全量；断言 ① 只覆盖 4 个导航/口径入口文档）继续阻断。

## 7. 维护与自测

- 新增规则：改 `scan-lib.sh` 里的 `scan_rule_secret` / `scan_rule_path`，**所有前缀字面量继续用拼接写法**（例如把通用前缀写成两个片段相加），否则本目录脚本自身会被规则命中，全历史扫描时会自报；
- 自测三条规则（无需提交、不改动仓库真实索引：让 `GIT_INDEX_FILE` 指向临时索引后调用钩子）：

```sh
# 用临时索引自测，不改动仓库真实索引
IDX=/tmp/scan-test-index
GIT_INDEX_FILE=$IDX git read-tree HEAD
printf 'api_key: %s%s\n' 's' 'k-AbCdEf0123456789ghijkl' > /tmp/t1.txt
GIT_INDEX_FILE=$IDX git add -f /tmp/t1.txt
GIT_INDEX_FILE=$IDX sh scripts/githooks/pre-commit   # expected: FAIL, blocked, exit code 1
```

- 改统一规范域、记录结构或全变更指纹算法：同步规范文档并升 `STANDARDS_RULE_VERSION`，不得修改或删除既有审查记录来制造通过；按项目授权执行相应验证，未执行项保持 `NOT_RUN`；
- 清理：删除临时索引与测试文件即可，仓库索引与工作树不受影响。
