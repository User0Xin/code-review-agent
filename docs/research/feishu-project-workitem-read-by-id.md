# 飞书项目工作项：服务端只凭 ID 能否读取、用什么凭据、描述是什么格式

本文只记录飞书项目（Feishu Project，国际版名为 Meegle）官方开发者手册、官方 SDK 与官方 CLI 中与这三个问题直接相关的事实。查阅日期为 2026-10-15。每段事实后面是拥有该事实的页面 URL。

- 开发者手册页面形如 `https://project.feishu.cn/b/helpcenter/1p8d7djs/<页面ID>`。这些页面由前端渲染，直接抓取 HTML 拿不到正文；本次通过浏览器渲染后阅读，内容来自页面本身。
- 国际版 meegle.com 的帮助中心本次没有逐页核对。Open API 的域名取实际访问域名，见第 2 节。
- 仓库里本地安装的 meegle skill 只用来找命令名线索。凡是只在该 skill 里出现、官方页面没有写的内容，文中都标为“来自本地 CLI skill”。

## 结论

1. **只凭工作项 ID 不能直接读。** 读详情的接口是 `POST /open_api/:project_key/work_item/:work_item_type_key/query`，路径里的空间（`project_key` 或 `simple_name`）和工作项类型 `work_item_type_key` 都是必填，请求体 `work_item_ids` 一次最多 50 个。官方常见问题原文是“只有工作项 ID 无法调用通过 Open API 接口查询”，给出的办法是先用“获取指定的工作项列表（全局搜索）”`POST /open_api/compositive_search` 拿到 `ProjectKey` 和 `WorkItemTypeKey`。限制：全局搜索页面写的是按标题、描述、人员等字段搜索，没有写工作项 ID 是可匹配字段，用 ID 当关键词能否命中要实测。已知空间时更稳的做法是：先用 `GET /open_api/:project_key/work_item/all-types` 取该空间全部类型，再用 `POST /open_api/:project_key/work_item/filter`，同时传 `work_item_ids` 和全部 `work_item_type_keys`，结果里带回 `work_item_type_key`。这个接口默认不返回已终止的工作项。分支前缀到类型的映射不能写死，自定义类型的 key 是随机字符串，必须按空间配置。
2. **服务端可用三种凭据，只有“插件访问凭证 + 指定用户”适合长期无人值守。**
   - `plugin_access_token`（`p-` 开头）用 Plugin ID 和 Plugin Secret 调 `POST /open_api/authen/plugin_token` 随时换取，有效期 7200 秒，过期后重新获取即可。调用业务接口时放在 `X-PLUGIN-TOKEN`，并且必须在 `X-USER-KEY` 里带一个具体用户；官方明确不支持以插件或机器人身份调用。前提是插件申请了对应接口权限、发布了版本，并由空间管理员安装到目标空间。
   - `virtual_plugin_token`（`v-` 开头）只供开发调试，只能访问“数据范围”里设的空间。
   - `user_access_token`（`u-` 开头）要求插件有前端构成，由用户在飞书项目页面里通过 JSSDK 拿授权码再换取。它有效期 7200 秒，`refresh_token` 有效期 1209600 秒（14 天），常见问题写明“最大刷新时间是14天”。它依赖用户交互，不能假定可以长期无人值守。
   - 风险点：`X-USER-KEY` 对应的人离职后，接口报 `10302`。默认的兼容鉴权模式下，读接口可能忽略 `X-USER-KEY`；加请求头 `x-auth-mode: 1` 才严格按该用户权限校验。
3. **描述有三种返回形态，可以直接拿到纯文本。** 描述是系统字段 `description`，类型 `multi_text`（富文本）。
   - 不带 `expand` 时，`fields[]` 里它的 `field_value` 是一个字符串，即富文本的简单内容（纯文本）。
   - 带 `expand.need_multi_text: true` 时，`multi_texts[]` 返回 `doc`（编辑器 JSON 字符串）、`doc_html`（HTML）、`doc_text`（纯文本）和 `is_empty`。
   - 带 `expand.need_rich_text_mark_down: true` 时返回 Markdown。参数表和官方 Java SDK 都把它放在 `field_value_markdown.markdown`；但同一页的返回示例把它写在 `field_value.rich_text_mark_down.markdown`，两处不一致，需要实测确认。
   - Markdown 里含飞书项目自定义语法，如 `<!-- mention:{...} -->`、`<!-- linkPreview -->`、`<span style=...>`。要直接交给模型，最省事的是 `doc_text`；要保留标题、列表、表格结构，用 Markdown 并自行去掉这些注释。另外，字段只有配置在新建页或曾经改过才会出现在 `fields[]` 里，读不到描述时要按空值处理。

## 1. 只拿到工作项 ID 时能否读到

### 读详情接口的必填标识

获取工作项详情：`POST /open_api/:project_key/work_item/:work_item_type_key/query`。

- Path 参数 `project_key` 必须，取值为空间 ID（`project_key`）或空间域名（`simple_name`）。`simple_name` 一般在空间 URL 中获取，例如 `https://project.feishu.cn/doc/overview` 的 `simple_name` 是 `doc`。
- Path 参数 `work_item_type_key` 必须，是创建工作项类型时自动生成的类型 key，通过“获取空间下工作项类型”接口获取。
- Body 参数 `work_item_ids` 必须，类型 `list<int64>`，一次最多 50 个。`fields` 可选，默认返回全部字段；`expand` 可选。
- 该接口的权限在“权限管理 - 工作项”分类下。
- 返回的每条数据带 `id`、`name`、`project_key`、`simple_name`、`work_item_type_key`、`pattern`（`Node` 节点流 / `State` 状态流）、`fields` 等。
- 接口错误码：`30005 WorkItem Not Found`（`work_item_ids` 不正确）、`20028 Workitem Ids Limit 50`、`30014 WorkItem Type Not Found`（`work_item_type_key` 错误）。

https://project.feishu.cn/b/helpcenter/1p8d7djs/r9jxqxnd

通用错误码表对 `30005` 的解释是：工作项未找到，可能是工作项已删除、查询的工作项 id 不正确、查询的工作项类型和工作项 id 不匹配。也就是说类型传错同样读不到。

https://project.feishu.cn/b/helpcenter/1p8d7djs/5aueo3jr

术语页对标识的定义：`project_key` 是系统定义的空间唯一标识，接口调用中使用；`simple_name` 是管理员定义的空间唯一标识，用于空间域名；`work_item_type_key` 是系统自动生成的工作项类型 key；`api_name` 是管理员定义的工作项唯一标识；`work_item_id` 是系统自动生成的工作项 ID。

https://project.feishu.cn/b/helpcenter/1p8d7djs/gcy4c27m

工作项 ID 的位数：常见问题写明实例 ID 自增从 30 亿开始，返回 `3xxxxxxxxx` 位数符合预期。工单里的 `7058321625` 这类 10 位 ID 在 `int64` 范围内。

https://project.feishu.cn/b/helpcenter/1p8d7djs/z2ox1bjb#a2bab930

### 官方常见问题：只有 ID 时先用全局搜索

常见问题“仅有工作项 ID，如何获取工作项信息，如 project_key、工作项类型”的回答原文是：“只有工作项 ID 无法调用通过 Open API 接口查询。需要先鉴权，参见获取访问凭证，然后通过获取指定的工作项列表（全局搜索）接口获取。”

https://project.feishu.cn/b/helpcenter/1p8d7djs/z2ox1bjb#9d17243a

获取指定的工作项列表（全局搜索）：`POST /open_api/compositive_search`。

- 页面描述：“按照标题、描述、人员等多字段和工作项类型，跨空间搜索符合条件的工作项实例列表”。权限在“权限管理 - 工作项实例”分类下。
- Body：`project_keys` 可选（空间 key 列表）；`simple_names` 可选；`query_type` 必须，取 `workitem` 或 `view`；`query_sub_type` 可选，是工作项类型列表，`query_type` 为 `workitem` 时生效；`query` 必须，是查询内容；`page_size` 默认 50；`page_num` 默认 1。
- 返回 `data[]` 遵循 `CompInfo`，字段包括 `ID`（工作项/视图 id，string）、`WorkItemTypeKey`、`ProjectKey`、`name`、`SearchHit`（命中字段 key 列表）、`CreatedAt`、`CreatedBy`。最多只返回匹配度最高的前 200 条。
- 错误码：`20080`（关键词为空或超过 200）、`20081`（`query_type` 填写错误）。
- 页面示例用名称“测试需求”搜索，`SearchHit` 为 `created_by`、`name`。页面没有写工作项 ID 是否属于可匹配字段。

https://project.feishu.cn/b/helpcenter/1p8d7djs/42w754bv

因此，“用 ID 作为 `query` 能命中该工作项”只有常见问题这一句话支撑，接口页本身没有写。接入前要用真实 ID 调一次确认。即使命中，也应只取返回中 `ID` 与目标 ID 完全相等的那一条（这是推断：全文搜索可能同时命中描述里恰好包含这串数字的其他工作项）。

### 已知空间时的替代路径

获取空间下工作项类型：`GET /open_api/:project_key/work_item/all-types`，`project_key` 同样可传 `simple_name`。返回 `data[]` 含 `name`、`api_name`、`type_key`、`is_disable`（1 已禁用，2 未禁用）。页面示例返回了 `story`（需求）、`issue`（缺陷）、`version`（版本）、`sprint`（迭代）、`chart`（图表）、`sub_task`（任务）。权限在“权限管理 > 配置”分类下。

https://project.feishu.cn/b/helpcenter/1p8d7djs/3pjp854w

类型 key 不能跨空间写死。常见问题写明：根据 `type_key` 识别，系统工作项如缺陷为 `issue`，自定义工作项为随机字符串。所以 `feature/`、`fix/`、`refactor/` 分别对应哪个类型，要按空间配置并用上面的接口校验。

https://project.feishu.cn/b/helpcenter/1p8d7djs/z2ox1bjb#f39bb535

获取指定的工作项列表（单空间）：`POST /open_api/:project_key/work_item/filter`。

- Path `project_key` 必须（可传 `simple_name`）。
- Body 中 `work_item_type_keys` 必须，是类型列表，可以一次传多个；`work_item_ids` 可选，是 `list<int64>`；`page_size` 最大 200。
- `expand` 支持 `need_workflow`、`need_multi_text`、`need_user_detail`、`need_sub_task_parent`、`relation_fields_detail`、`need_rich_text_mark_down`。
- 返回 `data[]` 带 `id`、`work_item_type_key`、`project_key`、`fields`。
- 注意事项原文：默认查询数据返回中不包含已终止工作项，已终止状态与其他状态互斥，需要分开查询再合并。

https://project.feishu.cn/b/helpcenter/1p8d7djs/1attl6vt

这条路径一次调用就能用“空间 + 全部类型 + ID”定位工作项并拿到描述。代价是已终止的工作项默认查不到。

获取指定的工作项列表（跨空间）：`POST /open_api/work_items/filter_across_project`。`work_item_type_key` 必须，且是单个字符串；`project_keys` 可选，不传时查询 header 中用户有权限、且安装了插件的空间；`work_item_ids` 可选；`page_size` 最大 50。它省掉了空间，但仍要类型。

https://project.feishu.cn/b/helpcenter/1p8d7djs/1hpwuxld

MQL 也不能只凭 ID 查询。MQL 语法要求 `FROM` 必须包含空间和工作项类型（`` FROM `project_key`.`工作项类型名` ``）。MQL 由飞书项目 MCP Server 的 `search_by_mql` tool 提供。

https://project.feishu.cn/b/helpcenter/1p8d7djs/tsl2uj3i

### 官方 meegle CLI 的 `workitem get`

`larksuite/meegle-cli`（npm 包 `@lark-project/meegle`）的 README 示例是 `meegle workitem get --work-item-id 12345`，以及在只注入 `MEEGLE_HOST` 和 `MEEGLE_USER_ACCESS_TOKEN` 时执行 `meegle workitem get --work-item-id 123`，示例中都没有传空间。README 说明 CLI 启动时从服务端获取可用命令列表；`auth status` 通过一次轻量 `tools/list` 调用校验 token。README 没有说明服务端如何只凭 ID 解析空间和类型。

https://github.com/larksuite/meegle-cli/blob/main/README.zh-CN.md

来自本地 CLI skill（未在官方文档确认）：`workitem get` 的 `--project-key` 标为可选。

这条路径走的是用户 OAuth 凭据，不是 Open API 的插件凭证（见第 2 节末尾），也不是 Java 服务能直接调用的 HTTP 接口。

## 2. 服务端调用的身份与凭据

### 请求头与域名

Open API 的 URL 形如 `https://{Base URL}/open_api/...`。`{Base URL}` 按实际访问域名替换，例如使用 `project.feishu.cn` 时就替换为 `project.feishu.cn`。

Header：

- `Content-Type: application/json`，必须。
- `X-PLUGIN-TOKEN`，必须。可选两种凭证：插件身份凭证 `plugin_token`（等同于 `plugin_access_token`），需要配合 `X-USER-KEY` 使用；或用户身份凭证 `user_plugin_token`（等同于 `user_access_token`）。
- `X-USER-KEY`，可选。使用插件身份凭证时，指定接口调用的用户 `user_key`。
- `X-IDEM-UUID`，可选，写接口的幂等串。
- `x-auth-mode`，可选，见下文。

https://project.feishu.cn/b/helpcenter/1p8d7djs/4bsmoql6

### 三种访问凭证

| 凭证 | 示例前缀 | 用途 |
| --- | --- | --- |
| `plugin_access_token` | `p-` | 插件访问凭证，用于获取用户访问凭证，或配合 `X-User-Key` 调用 OpenAPI |
| `virtual_plugin_token` | `v-` | 特殊的插件访问凭证，仅供开发调试，调用的空间范围受数据范围影响 |
| `user_access_token` | `u-` | 用户对插件的临时授权，插件代表用户执行操作 |

https://project.feishu.cn/b/helpcenter/1p8d7djs/4id4bvnf#2a1d4e7f

开发服务端和调试过程中使用 `virtual_plugin_token`；插件发布后，安装插件并运行则使用 `plugin_access_token`。

https://project.feishu.cn/b/helpcenter/1p8d7djs/4id4bvnf#947b5e1c

#### plugin_access_token

- 获取：`POST https://{域名}/open_api/authen/plugin_token`，Body 为 `plugin_id`（必填）、`plugin_secret`（必填）、`type`（可选，默认 `0` 返回 `plugin_access_token`，为 `1` 时返回 `virtual_plugin_token`）。Plugin ID 和 Plugin Secret 在开放平台插件详情页的基本信息里查看。
- 返回：`data.token` 和 `data.expire_time`（示例为 `7200`）。
- 有效期 7200 秒（2 小时）。有效期内重复获取返回相同结果，过期后获取返回新的 token。开发者需要缓存它。

https://project.feishu.cn/b/helpcenter/1p8d7djs/4id4bvnf#dd03503d

常见问题补充：有效期小于 5 分钟时，调用获取接口会返回全新 token，同时老 token 依然有效。它失效后不用刷新，到期后重新获取即可。

https://project.feishu.cn/b/helpcenter/1p8d7djs/61dmhkek#25009dcc

文档没有提到 Plugin Secret 会过期。只要 Plugin Secret 不变，服务端可以随时自行换取新 token，这是它适合无人值守的原因。

#### virtual_plugin_token

- 用 `type: 1` 调同一个接口获取，只供开发调试。
- 使用它时不受“空间是否安装了有对应权限的插件版本”限制，但只能获取被设为数据范围的空间内的数据。数据范围在插件权限管理中设置。

https://project.feishu.cn/b/helpcenter/1p8d7djs/4id4bvnf#c0d4c50f

开发联调阶段用虚拟 Token 不需要发布插件，但只支持获取单个空间的数据，空间范围是插件管理员具备管理权限的空间。

https://project.feishu.cn/b/helpcenter/1p8d7djs/5bkub0oc

#### user_access_token

- 前提：插件已添加插件构成，因为需要识别当前操作的用户身份。
- 第一步：在插件前端用客户端 API `window.JSSDK.utils.getAuthCode()` 获取授权码 `code`。授权码可多次获取，每个有效期 5 分钟，只能使用一次。
- 第二步：服务端调用 `POST https://{域名}/open_api/authen/user_plugin_token`，Header `X-Plugin-Token` 填 `plugin_access_token`，Body 为 `code` 和 `grant_type: "authorization_code"`。返回 `token`、`expire_time`（7200）、`refresh_token`、`refresh_token_expire_time`（1209600）、`saas_tenant_key`、`user_key`。不能在前端插件直接调用 OpenAPI，需要在自己的服务端发起。
- 第三步（可选）：`POST https://{域名}/open_api/authen/refresh_token`，Body 为 `refresh_token` 和 `type: "1"`，返回新的 `token` 与 `refresh_token`。该接口的 Header 表把 `X-Plugin-Token` 写作 `plugin_access_token`，示例值却以 `u-` 开头，两处不一致。

https://project.feishu.cn/b/helpcenter/1p8d7djs/4id4bvnf#3df3fccd

常见问题写明：通过刷新 Token API 刷新，最大刷新时间是 14 天。

https://project.feishu.cn/b/helpcenter/1p8d7djs/61dmhkek#5e84744f

文档没有说明刷新能否无限续期。授权码只能由用户在飞书项目页面里通过插件前端产生，所以这条路径依赖真人交互。

### 插件身份必须带一个具体用户

- 常见问题“如何以插件/机器人的身份使用 OpenAPI？”的回答：不支持以插件/机器人的身份调用 OpenAPI（Header 中的 `X-USER-KEY` 必须传递具体人员）。
- 常见问题“通过插件身份凭证 + X-USER-KEY 的方式请求 API，对 X-USER-KEY 的权限有什么要求”的回答：需要该用户在空间里有对应权限，例如管理员才能做的操作，`user_key` 也要有管理员权限。
- 常见问题“如何获取 user_access_token 和 refresh_token”中写明：若插件只涉及服务端使用 OpenAPI，建议使用 `plugin_access_token`，`plugin_access_token` 加请求头 `X-USER-KEY` 的作用等同于 `user_plugin_token`。

https://project.feishu.cn/b/helpcenter/1p8d7djs/61dmhkek#2c6209b8

https://project.feishu.cn/b/helpcenter/1p8d7djs/61dmhkek#96c7db25

https://project.feishu.cn/b/helpcenter/1p8d7djs/61dmhkek#aa8f30af

`user_key` 的来源：在飞书项目空间双击自己的头像获取；租户内其他成员的 `user_key` 通过“搜索租户内的用户列表”接口获取。

https://project.feishu.cn/b/helpcenter/1p8d7djs/568y2esm

与 `X-USER-KEY` 相关的错误码：

- `20039 Plugin Token Must Have User Key, But X-USER-KEY Is Not Set In Request Header`：使用应用 token 时请求头必须带 `X-USER-KEY`。
- `20042 X-User-Key Is Wrong, Please Check First`：未填或填错。
- `10211 Token Info Is Invalid`：包括插件级 token 未传 `X-USER-KEY`。
- `30006 User Not Found`：header 中的 `user_key` 找不到对应用户。
- `10302 Check User Error, User Is Resigned`：用户已离职。

https://project.feishu.cn/b/helpcenter/1p8d7djs/5aueo3jr

对无人值守服务的含义：要选一个长期在职、对目标空间有读权限的账号作为 `X-USER-KEY`。该账号离职后调用会失败。

### x-auth-mode：读接口按谁的权限校验

`x-auth-mode` 只在使用插件访问凭证（`plugin_access_token` 或 `virtual_plugin_token`）调用部分只读接口时生效：

- 值为 `1`：强制校验，严格使用 `X-User-Key` 的用户身份校验权限，不返回该用户没有读权限的资源。用户无权限时请求失败并返回权限相关错误码。
- 值为 `0` 或不传：兼容模式，也是默认模式。系统可能忽略 `X-User-Key`，使用内部虚拟身份确保数据能被成功读取，因此可能拿到 `X-User-Key` 对应用户无权访问的内容。
- 对 `user_access_token` 没有影响，用户凭证始终按该用户身份严格校验。对写接口和不在列表中的读接口也没有影响。
- 生效的接口包括本文涉及的 `POST /open_api/:project_key/work_item/:work_item_type_key/query`、`POST /open_api/:project_key/work_item/filter`、`POST /open_api/work_items/filter_across_project`、`POST /open_api/compositive_search`、`GET /open_api/:project_key/work_item/all-types`。

https://project.feishu.cn/b/helpcenter/1p8d7djs/x7bjo7aw

### 插件访问凭证在生产环境需要的授权

接口是否允许调用、数据是否返回，取决于三点：插件是否申请了对应权限；对应空间是否安装了拥有该权限的插件版本；用户是否拥有对应空间或数据的访问或编辑权限。

https://project.feishu.cn/b/helpcenter/1p8d7djs/4id4bvnf#c0d4c50f

申请权限在开放平台插件详情页的“权限管理”里勾选，Open API 权限按用户、空间、工作项实例、配置、评论、视图&度量分类。开发完成后需要发布插件并选择可见空间范围；插件上架后，空间管理员安装插件并完成空间数据授权，才能正式获取该空间的数据。企业管理员和飞书项目官方在上架审核时会审核所需权限的合理性。

https://project.feishu.cn/b/helpcenter/1p8d7djs/5bkub0oc

发布企业插件：在插件详情页“管理 > 插件发布”创建版本，填写版本号、版本描述，选择产物版本和插件可见范围，然后申请发布。该页的前提条件写的是插件已完成服务端和客户端开发，客户端已上传发布产物。文档没有写明只用 Open API、没有前端构成的插件能否跳过产物版本发布，需要在开放平台实际操作确认。

https://project.feishu.cn/b/helpcenter/1p8d7djs/61c1qm8q

`10301 Check Token Perm Failed` 的可能原因：没申请当前接口权限；申请了权限但未发布或未重新发布版本；发布了版本但未安装或更新插件；空间 ID 不存在；操作者没有对应空间权限。

https://project.feishu.cn/b/helpcenter/1p8d7djs/5aueo3jr

### 调用限制

服务端 API 对每个租户下每个插件调用单个接口限制 15 QPS。`/open_api/:project_key/work_item/filter` 和 `/open_api/work_items/filter_across_project` 同时限制 15 QPS 和 450 QPM。系统负载过高时，即使未超额度也可能限流，文档要求逐步延长重试间隔。超限错误码为 `10429 API Request Frequency Limit`。

https://project.feishu.cn/b/helpcenter/1p8d7djs/4bsmoql6

https://project.feishu.cn/b/helpcenter/1p8d7djs/5aueo3jr

### Java 服务端 SDK

飞书项目提供 Go 和 Java 服务端 SDK，SDK 会自动管理插件访问凭证的生命周期。Java SDK 为 `project-oapi-sdk-java`，最新版本 1.0.33，要求 Java >= 1.8。Maven 依赖为 `com.larksuite.project:oapi-sdk`，仓库地址 `https://artifacts-cn-beijing.volces.com/repository/meego/`，需要加到 `settings.xml`。

https://project.feishu.cn/b/helpcenter/1p8d7djs/3ajzsqlv

该 SDK 的 `Expand` 模型已包含 `need_multi_text` 和 `need_rich_text_mark_down`。

https://github.com/larksuite/project-oapi-sdk-java/blob/main/src/main/java/com/lark/project/service/workitem/model/Expand.java

### 备选入口：插件 MCP Server 与 meegle CLI

插件 MCP Server：在插件发布页开启后生成 URL，形如 `https://project.feishu.cn/mcp_server/v1?mcpKey=...&userKey=<userKey>`。和 Open API 一样，它会校验插件是否申请了相关权限、是否已安装到相应空间。配置 `userKey` 后，所有写入操作都以该用户身份执行。页面的 Tool 列表没有给出各 Tool 的参数，因此无法据此确认它能否只凭 ID 读取。

https://project.feishu.cn/b/helpcenter/1p8d7djs/jdmql9oj

meegle CLI 的认证是用户 OAuth：`meegle auth login` 走浏览器授权，`--device-code` 用于无浏览器环境。README 建议完全无人值守的 CI 用环境变量 `MEEGLE_USER_ACCESS_TOKEN` 注入 token；这种模式下 CLI 不访问 keychain，401 不会自动 refresh，由调用方自行轮转 token。README 没有说明该 token 的有效期。

https://github.com/larksuite/meegle-cli/blob/main/README.zh-CN.md

帮助中心里的“飞书项目开发工具 CLI”是插件开发工具 `lpm`（npm 包 `@lark-project/cli`），用于创建工程、调试、发布插件，不用于读取工作项数据。

https://project.feishu.cn/b/helpcenter/1p8d7djs/ijp3xr2d

## 3. 描述字段的返回格式

### 字段类型

描述是系统内置字段，字段 key 为 `description`，类型为 `multi_text`，按富文本类型解析。富文本字段（`multi_text`）的出参分两种：简单内容为 `string`；详细内容（如获取工作项实例时带 `expand` 相关参数）为结构体。

https://project.feishu.cn/b/helpcenter/1p8d7djs/1tj6ggll

### 不带 expand：纯文本字符串

字段与属性解析格式页的出参示例：入参是带颜色、斜体、下划线的富文本“文本”，简单出参为 `"field_value": "文本\n"`，没有格式信息。

https://project.feishu.cn/b/helpcenter/1p8d7djs/1tj6ggll

获取工作项详情的返回示例中，描述出现在 `fields[]` 里：

```json
{
  "field_key": "description",
  "field_value": "文档测试",
  "field_type_key": "multi_text",
  "field_alias": "description"
}
```

同一页对 `fields` 的说明：字段只有配置在新建页，或者曾经更改过，才会返回，否则不会出现在返回值中；做判空时要把“不在返回值中”也当作空。

https://project.feishu.cn/b/helpcenter/1p8d7djs/r9jxqxnd

### need_multi_text：doc、doc_html、doc_text

`expand.need_multi_text` 为 `true` 时返回 `multi_texts[]`，结构遵循 `MultiText`。每项含 `field_key` 和 `field_value`，`field_value` 遵循 `MultiTextDetail`：

- `doc`：富文本 doc 信息，是编辑器 ops 的 JSON 字符串。
- `doc_html`：富文本的 HTML 格式内容。
- `doc_text`：富文本纯文本信息。
- `is_empty`：是否为空。
- `notify_user_list`、`notify_user_type`：加了 @ 用户时的通知用户列表与类型。

示例：

```json
"multi_texts": [
  {
    "field_key": "description",
    "field_value": {
      "is_empty": false,
      "doc_html": "<div class=\"ace-line\" data-node=\"true\" dir=\"auto\"><span data-string=\"true\" data-leaf=\"true\">文档测试</span>...</div>",
      "doc": "{\"0\":{\"ops\":[{\"insert\":\"文档测试\\n\"}],\"zoneId\":\"0\",\"zoneType\":\"Z\"}}",
      "doc_text": "文档测试"
    }
  }
]
```

https://project.feishu.cn/b/helpcenter/1p8d7djs/r9jxqxnd

https://project.feishu.cn/b/helpcenter/1p8d7djs/1x1d372l#58feece0

### need_rich_text_mark_down：Markdown

获取工作项详情的 `expand` 参数表：

- `need_user_detail`：是否返回用户详细信息，默认 `false`。
- `need_workflow`：是否需要 workflow 信息，目前只支持节点流。
- `need_multi_text`：是否需要富文本信息。
- `need_sub_task_parent`：是否需要子任务的父级信息。
- `relation_fields_detail`：是否返回工作项关联详细信息。
- `need_rich_text_mark_down`：是否需要返回富文本 Markdown 格式信息。
- `need_sub_workitem_detail`：是否返回节点子项关联详情。
- `need_group_uuid_for_compound`：是否补充复合字段组标识。

返回参数表里，Markdown 有两处位置：

- `fields[].field_value_markdown`，对象，含 `markdown`（富文本内容 Markdown 格式）。说明写着“和 `field_value` 二选一，仅富文本格式字段生效”。
- `multi_texts[].field_value_markdown`，对象，含 `markdown`，说明为扩展参数 `need_rich_text_mark_down` 为 true 时返回。

但同一页“带 expand 参数查询”的返回示例把 Markdown 写在 `multi_texts[].field_value.rich_text_mark_down.markdown`（示例值 `"文档测试\n"`），与参数表不一致。

https://project.feishu.cn/b/helpcenter/1p8d7djs/r9jxqxnd

官方 Java SDK 的 `MultiText` 模型字段为 `field_key`、`field_value`（`MultiTextDetail`）、`field_value_markdown`，与参数表一致；`MultiTextDetail` 只有 `doc`、`doc_text`、`is_empty`、`notify_user_list`、`notify_user_type`、`doc_html`，没有 `rich_text_mark_down`。实际字段位置以真实调用为准，解析时可以两处都兼容。

https://github.com/larksuite/project-oapi-sdk-java/blob/main/src/main/java/com/lark/project/service/workitem/model/MultiText.java

https://github.com/larksuite/project-oapi-sdk-java/blob/main/src/main/java/com/lark/project/service/workitem/model/MultiTextDetail.java

`need_rich_text_mark_down` 同样出现在单空间筛选 `/work_item/filter` 和跨空间筛选 `/work_items/filter_across_project` 的 `expand` 中。

https://project.feishu.cn/b/helpcenter/1p8d7djs/1attl6vt

https://project.feishu.cn/b/helpcenter/1p8d7djs/1hpwuxld

“获取指定的工作项列表（全量搜索）”`POST /open_api/view_search/universal_search` 的 `field_value_markdown` 还多一个 `mention_user_keys`（富文本中 @ 人的 `id` 与 `user_key`）。该接口的 `project_key` 和 `work_item_type_key` 都必须。

https://project.feishu.cn/b/helpcenter/1p8d7djs/vmr4o7vo

### Markdown 里有什么

富文本字段和评论场景下，Open API 的输入、输出都支持 Markdown。语法分为标准语法和飞书项目特殊语法。

- 标准语法包括加粗、斜体、删除线、一到三级标题、有序/无序/任务列表、引用、链接、图片、分割线、GFM 表格、代码块。四级及以下标题在展示态退化为普通大字号文本。
- 下划线用 `<u>`。
- 文字颜色、背景色、字号用 `<span style="...">`，颜色必须写成 `rgb(R, G, B)`。
- @ 提及：`@张三<!-- mention:{"id":"...","cn_name":"...","en_name":"...","email":"...","blockType":"AT_USER_BLOCK"} -->`。Open API 返回时还附带 `mention_user_keys`。
- 链接预览：链接后紧跟 `<!-- linkPreview -->`。
- 对齐：`<!-- center:start -->` 与 `<!-- center:end -->` 包裹（`right`、`justify` 同理）。
- 表情：`:表情名:`。
- 单元格里放块级内容时用 HTML `<table>`。
- 字段引用：`{{创建者}}<!-- mentionField:{...} -->`。
- 页面示例中的图片是 `![](https://project.feishu.cn/goapi/v5/platform/file/stream/download/...)` 形式的链接。

https://project.feishu.cn/b/helpcenter/1p8d7djs/i10jbjk4

### 交给模型时的取舍（推断，非官方说法）

- 只要正文文字：用 `doc_text`，或不带 `expand` 时 `fields[]` 里 `description` 的字符串值。两者都不带标题、列表、表格结构。
- 要保留结构：用 Markdown，并去掉上面列出的 HTML 注释（`<!-- mention:... -->`、`<!-- linkPreview -->`、`<!-- center:... -->`、`<!-- mentionField:... -->`）和 `<span style>` 包装。
- 图片在三种形态里都不是可读文本；Markdown 里只是一个下载链接，文档没有说明该链接是否需要鉴权。
- 不建议解析 `doc`（编辑器 ops JSON）或 `doc_html`，它们是给编辑器和渲染用的。

## 未确认、需要实测的事项

- 全局搜索 `compositive_search` 用工作项 ID 作为 `query` 能否命中。只有常见问题一句话支撑，接口页没有写。
- 只用 Open API、没有前端构成的插件，能否不选产物版本就发布并被空间安装。
- Markdown 实际出现在 `field_value_markdown.markdown` 还是 `field_value.rich_text_mark_down.markdown`。
- `user_access_token` 的刷新能否无限续期。常见问题只写“最大刷新时间是14天”。
- Plugin Secret 是否会过期或需要轮换。文档没有提到。
- meegle CLI / MCP 的 `workitem get` 在服务端如何只凭 ID 解析空间和类型，以及 `MEEGLE_USER_ACCESS_TOKEN` 的有效期。官方 README 都没有写。
- 国际版 meegle.com 的帮助中心与上述页面是否一致。本次没有核对。
