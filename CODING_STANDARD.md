# code-review-agent 编码规范

本文供开发者和审查器审查本仓库的改动。Java 规则只约束本服务，Vue 规则只约束 `web/`；审查其他仓库时，应读取对方自己的编码规范。项目行为与验收条件以对应的 issue 或 spec 为准。

## 已有明确依据的规则

**Java 接口。** 新增 Controller 时，把公共前缀放在类级 `@RequestMapping`，在每个端点方法上声明非空子路径。非流式端点返回带类型参数的 `Result<T>`；没有业务载荷时用 `Result<Void>`。显式声明 `text/event-stream` 的流式端点不套此响应。比如 GitLab webhook 在类上声明 `/gitlab/webhooks`，方法只声明 `/merge-requests`。[接口纠正记录](https://github.com/User0Xin/code-review-agent/commit/8a26b4fb9422a99a35a35d1adc8c0b5a217be30b)；[架构测试](src/test/java/com/agent/codereviewagent/architecture/ArchitectureRulesTest.java)。

**HTTP 输入与服务边界。** Controller 的 `@RequestBody` 使用 `models.dto` 包中的类型；Service 接收 DTO 或领域对象，不依赖 Jackson 的 `JsonNode`。例如收到平台字段 `object_attributes` 时，在 DTO 上做字段映射，再把类型化的请求传给服务，不在服务内逐项解析 JSON 树。上述架构测试分别检查请求体类型和 Service 依赖。[历史改动](https://github.com/User0Xin/code-review-agent/commit/8a26b4fb9422a99a35a35d1adc8c0b5a217be30b)。

**命名和文字。** 命名审查流程、记录和测试时，先对照根目录的 [领域术语表](GLOSSARY.md) 和[领域文档约定](docs/agents/domain.md)。写回评审平台的文字使用中文，保留代码标识、文件路径和报错原文；服务生成规范修订时，替换文本跟随目标文档的语言，无法判定时用中文。这条语言约定仅适用于这些输出。[仓库维护者在 issue #7 中的决定](https://github.com/User0Xin/code-review-agent/issues/7#issuecomment-6188442152)。

**Vue 前端。** `web/src/pages/<页面>/index.vue` 负责展示与交互。单页私有组件放在该页的 `components/`，跨页组件放在 `web/src/components/`。把 HTTP 调用集中到 `requests/`：例如审查列表页调用查询函数，不直接导入 Axios。与页面无关的业务逻辑放在 `business/`，通用 TypeScript 工具放在 `utils/`。路由、共享 Pinia 状态、翻译、全局样式分别沿用 `router/`、`stores/`、`i18n/`、`styles/`。沿用脚手架的 ESLint、Prettier、EditorConfig 和 TypeScript 配置；规则的具体值以配置为准。[指定脚手架的目录说明及配置](https://github.com/User0Xin/vue-project/tree/046b1b5094aaa73a48616bed81192ffc1fc7a2e0)。

## 从历史重构归纳的惯例

下列是根据已合入的改动归纳的做法，不是维护者逐字提出的要求。审查时应说明它在当前改动中能解决什么问题。

- **持久化按业务操作组织。** Controller 调用 Service；服务编排通过对应的持久化服务读写记录，Mapper 负责数据库访问。需要把多项写入作为一次业务操作时，在服务方法上界定事务。已有 Service/Mapper 足以表达的操作，不再加一层只做转发的 Store。[审查记录和发现的分层重构](https://github.com/User0Xin/code-review-agent/commit/2d9ff40f9feced8a20aedd4a0810d78faae9ed65)。
- **让小规则靠近使用处。** 只有一个实现使用的短小路径判断，可以先作为其私有方法；不要为尚不存在的调用方设计独立接口。交付时移除已失去调用方的运行时代码和占位实现，保留测试实际使用的替身。[合并路径规则](https://github.com/User0Xin/code-review-agent/commit/d50dcbfe0678e4c091922c75eddfd795903a933d)；[清理无用实现](https://github.com/User0Xin/code-review-agent/commit/5d529076d5d13c1fdca8774991621703c2d8c0b6)。
- **注释说明判断的原因。** 对“收到更新却不启动审查”“多个失败只保留最新说明”等容易误读的分支，用简短中文说明触发条件和意图，无需给每行赋值加注释。[补充规则注释的改动](https://github.com/User0Xin/code-review-agent/commit/9b8ac6bc101c94e61d84efeec746cc95e52a05cd)。
