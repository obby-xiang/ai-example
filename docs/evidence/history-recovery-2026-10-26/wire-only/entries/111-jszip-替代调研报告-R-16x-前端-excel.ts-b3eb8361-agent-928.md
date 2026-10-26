> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-928` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：16041 B / 10699 字符 / 120 行；行指纹覆盖率（仓内 + 落盘语料）2.5%
> - 类别：**正式独立报告**
> - 脱敏：路径 1 / 会话标识 1 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# jszip 替代调研报告（R-16x · 前端 excel.ts）

**DC-19 自证（首轮）**：`agentId":"agent-928","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore"`，会话 `session_<KIMI_SESSION>-811f-46a6-845e-303a104c02f8`（wire.jsonl 首条 `profile.bind` 单行实证）。

---

## 0. 落点事实核对（先纠一处任务前提）

`<REPO_ROOT>\frontend\src\utils\excel.ts` 里 JSZip **有三处**，不是两处：

| 位置 | 用途 | 状态 |
|---|---|---|
| `excel.ts:622-635`（`buildXlsx` 内） | 自写 OOXML 字符串 ×7 + N 张 sheet → xlsx Blob | 在产线 |
| `excel.ts:790-805`（`zipBlobs`） | 多 Blob 打一个 zip，重名 `(2)` 后缀 | 在产线（`ExportWizardView.vue:976/1010/1037/1382`） |
| `excel.ts:777-787`（`unzip`）+ `ZipEntry` | 解 ZIP | **死代码**：全仓 `src` 只有定义行，无任何调用点 |

补充证据：`package.json:24` `"jszip": "^3.10.1"`；测试侧 `tests/export-wizard-batch-download.spec.ts:66-69`、`export-wizard-step-outcome.spec.ts:74` 都是 `vi.mock('@/utils/excel')` + `zipBlobs: vi.fn()`，**没有任何断言真实 zip 字节**——换库是容器内改动，不动测试口径。另：`package.json` 里 `jszip` 是唯一 `^` 半松版本的四件套外依赖（其余 markdown-it/dompurify/hljs/mermaid 都是精确 pin），换库正好顺手收口版本策略。

结论：**换库只需改 `excel.ts` 三处（实际两处）+ 删死代码**，而且 `unzip`/`ZipEntry` 可以整块删掉——「无需解压」的前提成立。

---

## 1. 候选逐库对比

数据源：npm registry（`registry.npmjs.org`，2026-10-15 实查）+ `api.npmjs.org/downloads/point/last-week`（区间 2026-10-06~10-12）+ bundlephobia / jsDelivr 实测字节数。

| | **fflate** | **@zip.js/zip.js** | **client-zip** | （现状 jszip） |
|---|---|---|---|---|
| License | **MIT**（单协议） | BSD-3-Clause | **MIT**（单协议） | `(MIT OR GPL-3.0-or-later)` ← 双协议 |
| 运行期依赖 | **0** | **0** | **0** | 4（lie/pako/setimmediate/readable-stream） |
| 最新版 / 发布时间 | **0.8.3 / 2026-05-16** | 2.23.0 / 2026-10-03 | 2.5.1 / 2026-09-14 | 3.10.2 / 2026-09-08 |
| 发布节奏 | 58 个版本；0.4.9/0.5.4/0.6.11/0.7.5 于 **2026-07-20 批量回补发布**（旧线维护） | 379 个版本，**近乎每周一发** | 47 个版本；1.x/2.x 双线同日发（2.5.1 与 1.7.1 均 09-14） | 39 个版本，2022 后基本停更 |
| 周下载量 | **99,318,677** | 6,606,262 | 492,387 | 48,917,554 |
| GitHub | 101arrowz/fflate · 3050★ · 50 open issue · pushed 2026-05-16 | gildas-lormeau/zip.js · 3900★ · **1** open issue · pushed 2026-10-03 | Touffy/client-zip · 449★ · 8 open issue · pushed 2026-09-14 | Stuk/jszip · 10389★ · 389 open issue |
| 体积（实测字节） | bundlephobia：**31.1 kB min / 11.8 kB gz（整库）**；UMD `index.js` 33,044B/12,630B gz；ESM 未压缩 91,765B/22,608B gz；**只用 zip 子集（`zipSync`+`ZipPassThrough`+`strToU8`+crc）按 tree-shaking 后通常个位数 kB** | `index.min.js` **209,666B（≈89 kB gz）**；`index-native.min.js` 210,742B；npm 包解包 8.7 MB / 114 文件 | `index.js` **6,476B / 2,679B gz** | `jszip.min.js` 97,630B / 28,400B gz |

### 1.1 fflate（重点候选）

- **API 匹配度：最高，近乎 drop-in。** 输入只接受 `Uint8Array`（`AsyncZippableFile = Uint8Array | AsyncZippable | [Uint8Array | AsyncZippable, AsyncZipOptions]`，index.d.ts:1024），所以：
  - `buildXlsx`（excel.ts:622-635）：字符串直接 `strToU8()`，一次 `zipSync({ '[Content_Types].xml': strToU8(contentTypes), '_rels/.rels': ..., 'xl/workbook.xml': ..., 'sheet1.xml': ... })` → 返回 `Uint8Array`，`new Blob([u8], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })`。**原 7 行 `zip.file()` 变成一次对象字面量**，sheet 数动态时用 `Object.fromEntries` 或路径键名拼装，代码量持平。
  - `zipBlobs`（excel.ts:790-805）：**只改最后两行**——重名编号逻辑原样保留，`zip.file(name, blob)` → 先 `await Promise.all(entries.map(e => e.blob.arrayBuffer()))` 再 `zipSync(record, { level: 0 })`。
- **同步 API 是它的卖点**（README：「The compressor, in synchronous mode…」），buildXlsx 本来就是 async 函数，同步实现零适配成本。
- **压缩方法可控且语义清晰**：源码 `var compression = p.level == 0 ? 0 : 8;` → `level: 0` = STORE(0)，否则 DEFLATE(8)；默认 `level` 落在 6（`dflt(dat, opt.level == null ? 6 : opt.level, ...)`）。
- **Excel 友好度已核到字节级**：`wzh()` 写 local/central header 时 **version-needed 硬编码 20**，且 `os` 字段默认未设 → Uint8Array 写入得 **OS 字节 0（MS-DOS/FAT）**。这正是 Excel 的挑刺点（见 §3 的 7z 反例），fflate 天然合规。
- **已知风险**：
  1. **异步 API（`zip`/`Zip`/`unzip`）走 Worker**，README 明说「asynchronous version of every method… run in a separate thread by using Web Workers」，实现是 `new Worker(ch[id] || (ch[id] = URL.createObjectURL(new Blob([...]))))`（browser.js:12）→ **要求 `worker-src blob:` 允许**；严格 CSP 的企业壳子会被拦（有 50ms 冷启开销，且 <50kB 负载反而比同步慢）。规避：本次两个用途都用同步 `zipSync`，**完全不触发 Worker**。
  2. 默认 `level: 6` 会产生 DEFLATE，与现状（见 §3：JSZip 默认 STORE）**字节行为不同**：导出体积变小但 CPU 上升。要行为等价就显式 `level: 0`；建议 buildXlsx 用 6（XML 压缩收益大且是 OOXML 最常见形态）、zipBlobs 用 0（避免对大 xlsx 二次压缩）。
  3. 大导出走同步 `zipSync` 会占主线程（现状 JSZip `generateAsync` + STORE 也是主线程拼装，但异步接口有让步）。真要防卡，可换 async `zip` + Worker，代价回到 CSP。
  4. 单维护者项目（101arrowz），50 个 open issue，最近一次 push 2026-05-16（约 5 个月前）——活跃度低于 zip.js，但旧大版本 2026-07-20 有批量回补发布，属「低频但仍在维护」。

### 1.2 @zip.js/zip.js

- **API：能覆盖，但更重**。两处写法：`const w = new ZipWriter(new BlobWriter(mime)); await w.add(name, new TextReader(xml), { level: 0 }); const blob = await w.close();`；`zipBlobs` 用 `new BlobReader(blob)` 直接吃 Blob（比 fflate 少一次手动 arrayBuffer，但多一层对象）。
- **风险/代价**：
  - **体积最大**：主构建 209 kB（≈89 kB gz），比 fflate 整库大 7 倍、比 jszip 也大 2 倍多——「替换 jszip 为更小的库」的目标基本落空，只因合规可接受。
  - **默认走 WASM/CompressionStream 编解码器**（d.ts:548「The URI of the WebAssembly module used by default implementations to compress/decompress data」）→ 额外资源加载与 CSP 面。
  - **`useWebWorkers` 默认启用**（WorkerConfiguration），同样有 CSP 面，需要显式关掉。
  - 379 个版本的周更节奏 = 供应链变更面大（合规重审频率高）。
  - 优点：维护最活跃（1 个 open issue）、能力上限最高（Zip64/加密/多核）、BSD-3 干净。

### 1.3 client-zip（备选）

- 6.4 kB min / 2.7 kB gz、MIT、0 依赖、**不做压缩**（README 原话「It does *not* compress the files or unzip existing archives」）→ 天然 STORE，语义与现状一致。
- API 是流式 Response/ReadableStream：`await downloadZip([{ name, input, lastModified }]).blob()`。
- **对 xlsx 有一票否决级风险**：README「Known Issues」明确写 **「MS Office documents must be stored using ZIP version 2.0; use client-zip^1 to generate those」**——v2 会声明 "ZIP 4.5 required" 并可能产 Zip64，「not readable by every ZIP reader out there」。也就是说 **v2 不能用于 `buildXlsx`，只能用于 `zipBlobs`（打包若干 xlsx 文件的普通 zip）**；要产 xlsx 得退到 1.x 线（npm `nozip64` tag）。
- 其他：需要 BigInt（ES2020，Vite 默认目标没问题）、SSR 框架打包有坑（本仓是 SPA，无碍）、维护是 1.x/2.x 双线并行（同日发两版）。
- 若「厨房 Sink 备选」指「打包下载那条链路单独换它」，技术上成立；但一个仓引两个 zip 库不划算。

### 1.4 其他候选（含排除理由）

- **archiver**：MIT，但**Node-only**——依赖 `async/buffer-crc32/lazystream/readable-stream/tar-stream/zip-stream/readdir-glob`，浏览器不可用。**排除。**
- **pako + 手写 zip**：pako 只做 DEFLATE，不含 zip 容器，等于「手写兜底 + 引一个库」，比纯手写更差。**排除。**
- **adm-zip / unzipper / yauzl**：Node 文件系统 API 或解压专用。**排除。**
- **浏览器原生 `CompressionStream('deflate-raw')`**：可做「零库 DEFLATE」路径（Chrome/Edge 103+、Node 18+ 均有），但仍需自写 zip 容器 + CRC32，即等价于 §3 的手写方案再加流处理复杂度。可作为手写方案的增强项记录，不建议首版引入。

---

## 2. 两问作答

### a. 哪个最可能已在大型企业合规白名单

**先说清证据边界**：字节/阿里的内部合规白名单不公开，我没有任何可引用的公开清单，下面是可用证据 + 推断，请不要当结论用。

- 能查到的实证：我抽查了中系大厂 OSS 的直接依赖——`@arco-design/web-react`、`@douyinfe/semi-ui`、`antd`、`@alilc/lowcode-engine` **zip 类直接依赖均为零**；真正在用 jszip 的是 `exceljs`（直接依赖 `jszip`+`archiver`+`unzipper`）与 `docx`（直接依赖 `jszip`）。也就是说生态盘子基本被 jszip 占着，**你们被禁的不是「没人用」，所以放行与否取决于该库自身的合规画像，而非流行度**。
- jszip 被禁的最可能技术原因已经找到了：它的 license 字段是 **`(MIT OR GPL-3.0-or-later)` 双协议表达**（npm registry 实查），SCA 扫描器极易报「GPL/不确定」红灯；外加 `readable-stream`/`setimmediate` 这类 Node 垫片依赖会被安全扫描记一笔。三个候选都是**单协议 + 0 依赖**，SCA 面干净。
- 按「已被审过/已进 SBOM」的概率排序：**fflate（99.3M 周下载，比 jszip 还高一倍）> zip.js（6.6M，BSD-3）> client-zip（0.49M）**。fflate 是国内技术社区写「JSZip 替代」时的默认答案（[稀土掘金对比文](https://juejin.cn/post/7545698928760504383)、[CSDN 流式打包实战](https://bbs.csdn.net/weixin_32943857/article/details/100323247) 都指向它），合规同学大概率在别人的送审里见过，**送审阻力与澄清成本最低**。
- **可执行建议**：送审材料就按「MIT 单协议 + 0 运行期依赖 + 无 postinstall + 不含 Worker 使用（同步 API）+ 提供 SBOM 条目」这套打。fflate 的「无依赖 + 单协议」是三个里最不可能被追问的一组。

### b. 手写 STORE 模式 zip 作为「零依赖兜底」——**可行，且是本题唯一真正零依赖的路**

**zip 规范 STORE 条目结构（该写的就这 3 段 + CRC32）**

1. **Local file header（30 B 固定 + 文件名）**：`0x04034b50`(4) · version needed = **20**（2.0）(2) · 通用位标志(2，中文名置 bit11 `0x0800` UTF-8；**data descriptor 位必须为 0，因为大小已知**) · method = **0**（STORE）(2) · DOS 修改时间/日期(4，年份 -1980，注意 ≥1980) · CRC-32(4) · 压缩大小(4)=未压缩大小(4) · 文件名长(2) · 扩展区长 = 0(2) · 文件名(UTF-8) · 紧接着就是原始字节。
2. **Central directory header（46 B 固定 + 文件名）**：`0x02014b50`(4) · **version made by = `(OS<<8)|20`，OS 字节必须写 0（MS-DOS/FAT）**(2) · version needed = 20(2) · 标志(2) · method 0(2) · 时间/日期(4) · CRC(4) · 两个大小(4+4) · 文件名长(2) · 扩展/注释长 0(2) · 起始磁盘 0(2) · 内部属性 0(2) · 外部属性 0(2) · **本条目 local header 的相对偏移**(4) · 文件名。
3. **EOCD（22 B）**：`0x06054b50`(4) · 磁盘号 0(2) · CD 所在磁盘 0(2) · 本盘条目数(2) · 总条目数(2) · CD 字节长(4) · CD 偏移(4) · 注释长 0(2)。
4. **CRC-32**：IEEE 802.3 多项式 `0xEDB88320`，256 项查表 + 逐字节异或，约 12 行；对每个 part 的 UTF-8 字节跑一遍（`TextEncoder` 编码）。

**xlsx 接受 STORE 吗——接受，而且你们现在用的就是 STORE**

- 规范侧：文档容器（OPC 底座）对压缩方法的规定是「**may only be stored uncompressed or using the "deflate" mechanism**」（[Library of Congress FDD000361](https://www.loc.gov/preservation/digital/formats/fdd/fdd000361.shtml)）——**STORE 是规范允许的两选之一**（注意个别文献如 [FDD000363](https://www.loc.gov/preservation/digital/formats/fdd/fdd000363.shtml) 会简写成「restricted to deflate」，措辞偏严；实际读取器不拒 STORE）。
- 生态侧：`turbo-xlsx-rs` 的 v1 就是「**emits a STORED (uncompressed) OPC zip**」且正常可用（[Libraries.io](https://libraries.io/pypi/turbo-xlsx-rs-parse)）。
- **本仓最强证据**：`excel.ts:632-635` 调 `zip.generateAsync({ type: 'blob', mimeType })` **没传 `compression`**，而 [JSZip 文档](https://stuk.github.io/jszip/documentation/api_jszip/generate_async.html) 明确 `compression` 默认值 = **STORE（no compression）**。也就是说，**今天在产线跑的、被后端 POI `ExcelReader#readMetaSheet` 正常消费的那份 xlsx，本身就是 STORE-only**。手写 STORE 写出的是「同类文件」，不是新形态。

**必须避开的 Excel 坑（都有出处）**

1. **version needed / OS 字节**：7z 生成的 xlsx 在 Excel 里打不开，根因就是它在 version 字段写了 Unix(3)（[Stack Overflow 15033646](https://stackoverflow.com/questions/15033646/compression-method-for-xlsx-with-7z)，Mark Adler 的 `needz.c` 就是把 local+5 与 central+7 的 OS 字节清零）。手写时 version needed **固定 20**、version made by 的 OS 半边 **写 0**。
2. **不要 Zip64**：Office 文档要求 ZIP version 2.0（client-zip README「MS Office documents must be stored using ZIP version 2.0」）。我们的 part 全在 32 位范围（除非单 part > 4 GB，现实中不可能），所以不写 Zip64 extra field 即可。
3. **中文文件名**：`zipBlobs` 的条目名来自 `xlsxFileName()`（`CODE_名称.xlsx`，含中文）→ 必须 `TextEncoder` 编 UTF-8 且置 bit11 `0x0800`，否则 Windows 资源管理器乱码。
4. **扩展区长度留 0、不写 data descriptor、不写注释**，能少踩一票读取器的坑。
5. 若哪天要 STORE 之外的能力（DEFLATE），手写成本就上来了：得接 `CompressionStream('deflate-raw')` 并处理流式 CRC——届时不如直接用 fflate。

**成本估计**：一个 `zip-store.ts`（CRC32 表 + 写 header/EOCD + `crc32()` / `storeZip(entries: {name, bytes}[]): Uint8Array`）约 80-120 行 + 3-4 个单测（EOCD 偏移、CRC 已知向量 `"123456789"` → `0xCBF43926`、中文名标志位、多条目偏移累加）。零依赖、零供应链面，但 zip 写错就是「文件打不开」类故障，属**要自己长期维护的兼容性代码**。

---

## 3. 推荐排序（一句话）

**fflate（`level: 6` 用于 xlsx、`level: 0` 用于 zipBlobs，同步 API 不触发 Worker）> 手写 STORE 兜底（仅当合规连 fflate 都不批时）> client-zip v1（只作 zipBlobs 备选，v2 不可产 xlsx）> @zip.js/zip.js（能用但体积/依赖面全是回退）** —— 即：先送审 fflate，备好 §3 的 100 行零依赖 STORE 实现作为无条件退路。

**落地清单（供施工方参考）**：改 `excel.ts:18/622-635/790-805`、删 `unzip`/`ZipEntry`(`:777-787`) 与文件头 `:12` 的 JSZip 描述、`package.json` 换依赖并精确 pin（去掉 `^`）、顺手核 `AGENTS.md`/docs 里有无「JSZip」字样需同步、跑 `yarn test`（测试全是 mock，预期零改动）+ 真机验证一份含中文 sheet 名的 xlsx 能被后端 POI 读回。
