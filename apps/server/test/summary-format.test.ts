import { describe, expect, it } from "vitest";
import { summaryFormatError } from "../src/integrations/summary-format.js";

const valid = `### 适用范围

库存预订的数量判断及扣减实现。

### 支持证据

- PR #2：\`available > quantity\` 拒绝恰好用尽库存。
- PR #2：\`available + quantity\` 导致库存增加。

### 建议

1. 使用 \`>=\` 检查边界。
2. 断言扣减结果等于原库存减预订量。

### 局限

仅来自一个 PR，不构成全局规则。`;

describe("cross-PR summary Markdown validation", () => {
  it("accepts four ordered third-level headings with separated, non-empty sections", () => {
    expect(summaryFormatError(valid)).toBeUndefined();
  });

  it("accepts CRLF, surrounding whitespace, and whitespace-only blank lines", () => {
    const body = `\n${valid}\n`.replaceAll("\n\n", "\n \t\n").replaceAll("\n", "\r\n");
    expect(summaryFormatError(body)).toBeUndefined();
  });

  it("accepts normal Markdown in section content, including nested lists and code examples", () => {
    const body = valid.replace(
      "1. 使用 `>=` 检查边界。\n2. 断言扣减结果等于原库存减预订量。",
      "1. 验证边界。\n   - 恰好用尽库存也可预订。\n\n```ts\nexpect(reserve(5, 2)).toBe(3);\n```",
    );
    expect(summaryFormatError(body)).toBeUndefined();
  });

  it.each([
    ["empty text", ""],
    ["a single paragraph", "适用范围：库存。支持证据：PR #2。建议：扣减库存。局限：仅一个 PR。"],
    ["all headings on one line", valid.replaceAll("\n", " ")],
    ["missing section", valid.slice(0, valid.indexOf("### 局限"))],
    [
      "reordered sections",
      valid.replace("### 建议", "### 局限").replace(/### 局限(?=\n\n仅来自)/, "### 建议"),
    ],
    ["duplicated section", valid.replace("### 局限", "### 建议")],
    ["an extra heading", `${valid}\n\n### 补充\n\n更多内容。`],
    [
      "an extra subheading",
      valid.replace("库存预订的数量判断及扣减实现。", "#### 子标题\n\n库存预订。"),
    ],
    ["a second-level heading", valid.replace("### 适用范围", "## 适用范围")],
    ["a fourth-level heading", valid.replace("### 适用范围", "#### 适用范围")],
    ["inline heading labels", valid.replace("### 适用范围", "**适用范围**")],
    ["an escaped heading", valid.replace("### 适用范围", "\\### 适用范围")],
    ["preamble before sections", `总结如下：\n\n${valid}`],
    ["a fenced Markdown document", `\`\`\`markdown\n${valid}\n\`\`\``],
    ["a tilde-fenced document", `~~~markdown\n${valid}\n~~~`],
    [
      "indented code",
      valid
        .split("\n")
        .map((line) => `    ${line}`)
        .join("\n"),
    ],
    [
      "blockquote headings",
      valid
        .split("\n")
        .map((line) => `> ${line}`)
        .join("\n"),
    ],
    ["one quoted heading", valid.replace("### 支持证据", "> ### 支持证据")],
    ["one fenced heading", valid.replace("### 支持证据", "```md\n### 支持证据\n```")],
    [
      "a heading hidden in an HTML comment",
      valid.replace("### 支持证据", "<!--\n### 支持证据\n-->"),
    ],
    ["a heading inside an HTML block", `<div>\n${valid}\n</div>`],
    ["no blank line after a heading", valid.replace("### 适用范围\n\n", "### 适用范围\n")],
    ["no blank line before a heading", valid.replace("\n\n### 支持证据", "\n### 支持证据")],
    ["an empty first section", valid.replace("库存预订的数量判断及扣减实现。", "")],
    ["an empty last section", valid.replace("仅来自一个 PR，不构成全局规则。", "")],
    ["a comment-only section", valid.replace("库存预订的数量判断及扣减实现。", "<!-- 待补充 -->")],
    [
      "a reference-definition-only section",
      valid.replace("库存预订的数量判断及扣减实现。", "[evidence]: https://example.com"),
    ],
    [
      "an image-only section hidden by the renderer",
      valid.replace("库存预订的数量判断及扣减实现。", "![库存判断](https://example.com/stock.png)"),
    ],
    [
      "a reference-image-only section hidden by the renderer",
      valid.replace(
        "库存预订的数量判断及扣减实现。",
        "![库存判断][stock]\n\n[stock]: https://example.com/stock.png",
      ),
    ],
    ["a horizontal-rule-only section", valid.replace("库存预订的数量判断及扣减实现。", "---")],
    ["an empty code-block section", valid.replace("库存预订的数量判断及扣减实现。", "```\n```")],
    ["an empty list-item section", valid.replace("库存预订的数量判断及扣减实现。", "- ")],
  ])("rejects %s", (_name, body) => {
    expect(summaryFormatError(body)).toEqual(expect.any(String));
  });

  it("returns an actionable explanation identifying the invalid section", () => {
    expect(summaryFormatError(valid.replace("仅来自一个 PR，不构成全局规则。", ""))).toBe(
      "“### 局限”分节必须包含非空正文。",
    );
    expect(summaryFormatError(valid.replace("### 支持证据\n\n", "### 支持证据\n"))).toContain(
      "“### 支持证据”与前后正文之间必须保留空行",
    );
  });
});
