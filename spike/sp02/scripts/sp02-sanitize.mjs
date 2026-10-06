// 脱敏：把证据文件里的本机绝对路径 / 用户名 / 密钥替换为占位符，并归档到 docs/spike/logs/sp02/。
//   node sp02-sanitize.mjs <srcDir> <destDir> [--recursive]
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const SRC = process.argv[2];
const DEST = process.argv[3];
const RECURSIVE = process.argv.includes('--recursive');
mkdirSync(DEST, { recursive: true });

const RULES = [
  // 顺序敏感：长路径优先
  [/[A-Za-z]:[\\/]+temp[\\/]+ai-example-code[\\/]+ai-example-main-v2[\\/]+spike[\\/]+sp02/gi, '<MAIN_V2>/spike/sp02'],
  [/[A-Za-z]:[\\/]+temp[\\/]+ai-example-code[\\/]+ai-example-main-v2/gi, '<MAIN_V2>'],
  [/[A-Za-z]:[\\/]+temp[\\/]+ai-example-code/gi, '<REPO_ROOT>'],
  [/[A-Za-z]:[\\/]+temp/gi, '<REPO_ROOT>'],
  [/[A-Za-z]:[\\/]+Users[\\/]+[^\\/]+[\\/]+\.m2/gi, '<M2_REPO>'],
  [/[A-Za-z]:[\\/]+Users[\\/]+[^\\/]+/gi, '<USER_HOME>'],
  [/[A-Za-z]:[\\/]+Program Files[\\/]+JetBrains[^"',)\s]*maven3[^"',)\s]*/gi, '<MAVEN_HOME>/bin/mvn.cmd'],
  [/[A-Za-z]:[\\/]+Program Files[\\/]+Java[^"',)\s]*/gi, '<JDK_HOME>'],
  [/[A-Za-z]:[\\/]+Program Files[\\/]+Memurai[^"',)\s]*/gi, '<MEMURAI_HOME>/memurai-cli.exe'],
  [/\bsk-[A-Za-z0-9_-]{12,}/g, '***'],
  [/started by [A-Za-z0-9_]+/g, 'started by <USER>'],
];

let files = 0;
function walk(src, dest) {
  mkdirSync(dest, { recursive: true });
  for (const name of readdirSync(src)) {
    const p = join(src, name);
    const target = join(dest, name);
    if (statSync(p).isDirectory()) {
      if (RECURSIVE) walk(p, target);
      continue;
    }
    let text = readFileSync(p, 'utf8');
    for (const [re, to] of RULES) text = text.replace(re, to);
    writeFileSync(target, text);
    files++;
  }
}
walk(SRC, DEST);
console.log(`sanitized ${files} files: ${SRC} -> ${DEST}`);
