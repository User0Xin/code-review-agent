# 本机工作台验收

用浏览器操作真实 Vite 页面，经过真实 Spring API、SQLite、GitHub 适配器和模型适配器。两个外部服务由 Python 在本机临时端口模拟；仓库 URL 虽形如 HTTPS，但应用只向本机模拟 API 发请求。脚本创建自己的数据库和访问令牌，不需要真实凭据或真实 PR。

准备 JDK 21、Maven、脚手架支持的 Node.js 版本、Python 3.9+，在仓库根目录执行：

```sh
mvn -DskipTests package
npm ci --prefix web
python -m pip install playwright
python -m playwright install chromium
python scripts/workbench-smoke/run.py --artifacts target/workbench-smoke
```

使用系统 Chrome 时，可跳过下载 Chromium，改传 `--browser "C:/Program Files/Google/Chrome/Application/chrome.exe"`。设置 `JAVA_HOME` 时，脚本使用其中的 Java。脚本自动启动、关闭本地服务并清理临时数据库；截图只在指定 `--artifacts` 时保存。

浏览器依次登记两个仓库，填写各自的规范和 spec 路径、导入不同的 review Skill，再触发各自的 PR。第一个等待浏览器观察到运行中的任务后完成；第二个收到刻意构造的不合约定的模型 JSON，必须显示失败。脚本检查两个仓库的真实模型入参、失败说明、刷新后的历史隔离，以及桌面和窄屏的布局。

最后，浏览器禁用第二个仓库的自定义 Skill 并恢复内置指令。脚本停止首个 Spring 进程，使用同一份临时 SQLite 文件启动全新的进程，通过管理 API 核对两仓的文档位置、各自的有效 Skill（第一个仍为自定义）与两条终态记录。成功时输出以 `PASS:` 开头。此验收不能证明真实平台的权限配置或在线模型表现。
