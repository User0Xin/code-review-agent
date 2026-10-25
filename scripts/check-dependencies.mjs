import { readFileSync } from "node:fs";
const policy = JSON.parse(
  readFileSync(new URL("../dependency-policy.json", import.meta.url), "utf8"),
);
const errors = [];
for (const [path, allowed] of Object.entries(policy.workspaces)) {
  const manifest = JSON.parse(readFileSync(new URL(`../${path}`, import.meta.url), "utf8"));
  const names = Object.keys({
    ...manifest.dependencies,
    ...manifest.devDependencies,
  });
  for (const name of names)
    if (!allowed.includes(name))
      errors.push(`${path}: ${name} 未在依赖白名单中；先检查现有库能否表达该行为。`);
  for (const name of Object.keys(manifest.dependencies ?? {}))
    if (name in (manifest.devDependencies ?? {})) errors.push(`${path}: ${name} 重复声明。`);
}
if (errors.length) {
  console.error(errors.join("\n"));
  process.exitCode = 1;
} else console.log("依赖白名单通过：UI、图标、动画、状态和表单各使用约定实现。");
