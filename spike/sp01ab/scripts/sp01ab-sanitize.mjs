// 脱敏：把证据文件里的本机绝对路径 / 用户名 / 密钥替换为占位符。
//   node sp01ab-sanitize.mjs <srcDir> <destDir>
// 注：下方 RULES 中的本仓路径字面量正则是脱敏匹配源，功能必需，属命名纪律豁免项（DOC#5 裁决），勿按命名纪律改写。
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const SRC = process.argv[2];
const DEST = process.argv[3];
mkdirSync(DEST, { recursive: true });

const RULES = [
  // 顺序敏感：长路径优先
  [/[A-Za-z]:[\\/]temp[\\/]ai-example-code[\\/]ai-example-main-v2[\\/]spike[\\/]sp01ab/g, '<MAIN_REPO>/spike/sp01ab'],
  [/[A-Za-z]:[\\/]temp[\\/]ai-example-code[\\/]ai-example-main-v2/g, '<MAIN_REPO>'],
  [/[A-Za-z]:[\\/]temp[\\/]ai-example-code[\\/]ai-example-deepseek-v4-pro/g, '<REPO_ROOT>/ai-example-deepseek-v4-pro'],
  [/[A-Za-z]:[\\/]temp[\\/]ai-example-code/g, '<REPO_ROOT>'],
  [/[A-Za-z]:[\\/]temp/g, '<REPO_ROOT>'],
  [/[A-Za-z]:[\\/]Users[\\/][^\\/]+[\\/]\.m2/g, '<M2_REPO>'],
  [/[A-Za-z]:[\\/]Users[\\/][^\\/]+/g, '<USER_HOME>'],
  [/[A-Za-z]:[\\/]Program Files[\\/]JetBrains[^"',)\s]*maven3[^"',)\s]*/g, '<MAVEN_HOME>/bin/mvn.cmd'],
  [/[A-Za-z]:[\\/]Program Files[\\/]Java[^"',)\s]*/g, '<JDK_HOME>'],
  [/\bsk-[A-Za-z0-9_-]{12,}/g, '***'],
  [/started by [A-Za-z0-9_]+/g, 'started by <USER>'],
];

let files = 0;
for (const name of readdirSync(SRC)) {
  const p = join(SRC, name);
  if (!statSync(p).isFile()) continue;
  let text = readFileSync(p, 'utf8');
  for (const [re, to] of RULES) text = text.replace(re, to);
  writeFileSync(join(DEST, name), text);
  files++;
}
console.log(`sanitized ${files} files -> ${DEST}`);
