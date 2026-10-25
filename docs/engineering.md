# TypeScript 工程约定

## 检查入口

日常使用 `pnpm check`，CI 执行相同命令。它依次运行类型感知 ESLint、Prettier、类型检查、测试、构建与依赖白名单检查。`pnpm lint:fix` 修复可自动处理的规则；`pnpm format` 统一格式。

共享 TypeScript 配置启用 strict、索引越界检查、隐式返回检查、switch 穿透检查、override 检查以及 verbatimModuleSyntax。前端在共享约束上使用 Bundler 解析；后端与契约使用 NodeNext。测试文件也参与类型检查，生产构建只包含后端 src。

- 类型只用于编译时，使用 `import type` 或内联 `type` 导入。
- 每条声明只定义一个变量，条件和循环使用花括号。
- 外部输入先视为 `unknown`，由 Zod 或平台适配器的对象/列表守卫缩窄。
- 生产代码禁止非空断言和显式 any；异步操作要等待、返回或明确处理 Promise。
- 任务使用 `kind` 判别联合；入队和读取持久任务时校验载荷，业务处理中不再通过 `String(unknown)` 猜测类型。
- 业务错误使用 `DomainError`；HTTP 层负责状态码映射，未知错误返回通用 500。凭据和模型 SDK 响应不得直接进入错误响应。
- 模型错误按 HTTP 状态、超时、网络故障和结构化输出失败分类，审查轮次直接显示原因及处理提示。校验详情只展示白名单字段路径和预期类型，不转发 SDK 原始消息、请求、响应正文或模型输出。

## 模块组织

Controller 解析请求并调用业务操作；配置、审查和发布规则各有负责人。Nest 的 provider/factory 在 AppModule 集中装配，Controller 显式声明注入。工作流依赖仓库和模型接口，测试通过同一接口注入受控实现。

前端按 feature 组合页面、表单和交互，App 只承担导航与装配。通用 UI 组件使用 Dub UI 官方组件与默认主题，本地适配层只处理业务 API 和可访问性。

## 关系型存储与初始化

业务字段和关联写入明确列。只把整体读取的冻结材料、上下文与证据放入 JSON。Repository 提供按项目/会话的查询，HTTP 和工作流不执行原始 SQL，也不依靠通用实体模板保存任意对象。

跨对象操作在同步事务中完成；事务内不得调用模型或远端平台。发布先提交冻结的意图，远端调用完成后记录结果，网络结果不确定时先按 marker 对账。

当前开发阶段直接维护当前结构，不维护数据库迁移版本。`apps/server/sql` 一张业务表一个 SQL 文件，同一文件包含该表的索引、约束和历史保护触发器。表结构变更直接修改相应 SQL，并同步修改 `storage/schema.ts` 中的 Drizzle 类型化定义；两者应表达相同字段和关系。构建和部署需要同时保留 `apps/server/sql` 目录。

`storage/initialize-database.ts` 在一个 SQLite 事务中执行初始化文件，使用 `CREATE ... IF NOT EXISTS` 建立当前表、索引和触发器。初始化不记录迁移账本或 `user_version`，不升级旧结构，也不删除或重建已有表。已有开发数据库与当前结构不一致时，先备份，再按实际结构手动调整。

历史材料使用数据库保护：PR 快照、规范版本、每轮结论和经验来源不可更新或删除；发布意图不能改变正文、来源、快照和目标。当前问题状态与每轮结论分开，轮次的执行结果和过时资格分开。保留配置历史时使用停用/归档。

数据库结构初始化与业务版本是不同概念。项目规范仍按业务版本追加保存；审查轮次继续冻结 SHA、配置和证据，已有历史材料保持不可变。

## 参考

- [TypeScript 编译器配置](https://www.typescriptlang.org/tsconfig/)
- [typescript-eslint 类型感知检查](https://typescript-eslint.io/getting-started/typed-linting/)
- [NestJS 模块](https://docs.nestjs.com/modules)与[依赖注入](https://docs.nestjs.com/fundamentals/custom-providers)
