# 本机工作台验收

用浏览器操作真实生产 jar 内的页面，经过同源 Spring API、SQLite、GitHub 适配器和模型适配器，不启动 Vite。两个外部服务由 Python 在本机临时端口模拟；仓库 URL 虽形如 HTTPS，但应用只向本机模拟 API 发请求。脚本创建自己的数据库和访问令牌，不需要真实凭据或真实 PR。

准备 JDK 21、Maven、脚手架支持的 Node.js 版本、Python 3.9+，在仓库根目录执行：

```sh
mvn -Pproduction clean package
python scripts/workbench-smoke/jar_check.py
python -m pip install playwright
python -m playwright install chromium
python scripts/workbench-smoke/run.py --artifacts target/workbench-smoke
python scripts/workbench-smoke/details_check.py --artifacts target/details-check
```

使用系统 Chrome 时，可跳过下载 Chromium，改传 `--browser "C:/Program Files/Google/Chrome/Application/chrome.exe"`。设置 `JAVA_HOME` 时，脚本使用其中的 Java。脚本自动启动、关闭本地服务并清理临时数据库；截图只在指定 `--artifacts` 时保存。

`jar_check.py` 不需要 Playwright，只用标准库实际启动 jar 检查路由、资源、401/503、webhook 验签、任务去重、老 schema 升级和重启保留/中断规则；日志及结果在 `target/jar-check/`。它不代表浏览器验收。

浏览器脚本 `run.py` 依次登记两个仓库，填写各自的规范和 spec 路径、导入不同的 review Skill，再触发各自的 PR。第一个等待浏览器观察到运行中的任务后完成；第二个收到刻意构造的不合约定的模型 JSON，必须显示失败。脚本检查两个仓库的真实模型入参、失败说明、刷新后的历史隔离，以及桌面和 320px 窄屏布局。

最后，浏览器禁用第二个仓库的自定义 Skill 并恢复内置指令。脚本停止首个 Spring 进程，使用同一份临时 SQLite 文件启动全新的进程，通过管理 API 核对两仓的文档位置、各自的有效 Skill（第一个仍为自定义）与两条终态记录。成功时输出以 `PASS:` 开头。此验收不能证明真实平台的权限配置或在线模型表现。

`details_check.py` 使用独立临时数据库和本机平台/模型替身：升级旧任务表并放入同版本旧历史，验证详情不猜测关联；运行成功零发现、明确拒绝造成的部分发布和同版本重试，核对实际发布正文、数量及讨论链接。浏览器从真实 jar 打开详情并连点重试，检查服务端返回的任务定位、HTML 作为文本、桌面/320px 布局，以及跨仓库和同仓库切任务的延迟响应。随后强制结束一个已保存首条发现、尚无审查记录的进程，检查重启保留结果且不重放；再模拟记录已保存、任务尚未收尾的恢复窗口。

该脚本同样支持 `--browser` 指定系统 Chrome。`--skip-browser` 仅供后端定位，输出会明确标记浏览器未验，不能作为完整发布验收。
