# 领域文档

工程技能在探索代码库时，按本文消费本仓库的领域文档。

## 探索之前先读

- 仓库根目录的 **`GLOSSARY.md`**，或
- 若根目录存在 **`GLOSSARY-MAP.md`**：它指向每个上下文各自的 `GLOSSARY.md`。只读与当前主题相关的那些。
- **`docs/adr/`**：阅读即将改动的区域所涉及的 ADR。多上下文仓库还要查看 `src/<context>/docs/adr/` 中该上下文自己的决策。

这些文件若不存在，**静默继续**。不要指出它们缺失，也不要建议预先创建。`/domain-modeling` 技能（经由 `/grill-with-docs` 与 `/improve-codebase-architecture` 到达）会在术语或决策真正确定时再懒创建。

## 文件布局

本仓库是 single-context：

```
/
├── GLOSSARY.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多上下文仓库（根目录存在 `GLOSSARY-MAP.md`）的布局如下，本仓库当前不使用：

```
/
├── GLOSSARY-MAP.md
├── docs/adr/                          ← 全系统决策
└── src/
    ├── ordering/
    │   ├── GLOSSARY.md
    │   └── docs/adr/                  ← 该上下文自己的决策
    └── billing/
        ├── GLOSSARY.md
        └── docs/adr/
```

## 使用术语表里的词汇

输出中给领域概念命名时（issue 标题、重构提议、假设、测试名），使用 `GLOSSARY.md` 中的定义。不要改用术语表明确回避的同义词。

若需要的概念还不在术语表里，这是一个信号：要么是在发明项目并未使用的说法（应重新考虑），要么是真实缺口（记下来交给 `/domain-modeling`）。

## 标出与 ADR 的冲突

若输出与已有 ADR 矛盾，明确写出来，不要静默覆盖：

> _与 ADR-0007（event-sourced orders）矛盾，但值得重开，因为…_
