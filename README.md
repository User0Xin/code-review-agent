# Code Review Agent

一个基于 Spring Boot、Spring AI Alibaba 和 AgentScope 的中文 AI Agent 示例服务。

项目提供两种调用方式：

- `ChatClient`：直接进行单轮对话。
- `ReactAgent`：支持基于 `threadId` 的进程内会话记忆，并可选调用课程查询和当前时间工具。

> 当前仓库实现的是通用 AI 对话与 Agent 示例；“code review”目前还是项目名称，尚未提供代码审查专用接口。

## 技术栈

- Java 21
- Spring Boot 3.5.8
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.2
- AgentScope 2.0.3
- Maven

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- 一个 OpenAI 兼容的 Chat Completions 服务

### 配置模型服务

应用默认连接 `http://localhost:8080`，并请求其 `/v1/chat/completions` 接口。启动前可以通过环境变量覆盖配置：

```bash
export OPENAI_API_KEY="your-api-key"
export OPENAI_BASE_URL="https://your-openai-compatible-host"
export OPENAI_MODEL="your-model-name"
```

也可以直接修改 `src/main/resources/application.yaml`。其中 `OPENAI_BASE_URL` 应填写到端口或主机级别，不要重复添加 `/v1`。

### 启动

```bash
mvn spring-boot:run
```

服务默认监听 `http://localhost:8088`。

## API

### 单轮对话

```bash
curl --get 'http://localhost:8088/ai/chat' \
  --data-urlencode 'message=请介绍一下 Spring AI'
```

返回：

```json
{"answer":"..."}
```

### Agent 对话

使用 `threadId` 区分会话。同一个 `threadId` 在应用运行期间会保留上下文；记忆保存在进程内存中，应用重启后会丢失。

```bash
curl -X POST 'http://localhost:8088/ai/agent' \
  -H 'Content-Type: application/json' \
  -d '{"message":"我叫小明，请记住我的名字","threadId":"demo"}'

curl -X POST 'http://localhost:8088/ai/agent' \
  -H 'Content-Type: application/json' \
  -d '{"message":"我叫什么名字？","threadId":"demo"}'
```

也支持 GET：

```bash
curl --get 'http://localhost:8088/ai/agent' \
  --data-urlencode 'message=介绍一下 ReactAgent' \
  --data-urlencode 'threadId=demo'
```

### 流式 Agent 对话

接口以 Server-Sent Events 返回模型生成的文本：

```bash
curl -N --get 'http://localhost:8088/ai/agent/stream' \
  --data-urlencode 'message=介绍一下 ChatClient' \
  --data-urlencode 'threadId=demo'
```

## 启用工具调用

默认情况下工具调用关闭：

```yaml
demo:
  tools-enabled: false
```

如果使用的模型支持 OpenAI 兼容的 function/tool calling，可以在配置中启用：

```yaml
demo:
  tools-enabled: true
```

启用后，Agent 可以使用以下工具：

- `lookupCourse`：查询 `chatclient`、`react-agent` 或 `memory` 课程说明。
- `currentTime`：获取服务器当前时间。

请确认模型服务支持工具调用；不支持时保持 `tools-enabled: false`。

## 开发与测试

运行测试：

```bash
mvn test
```

构建可执行 JAR：

```bash
mvn clean package
java -jar target/demo01-0.0.1-SNAPSHOT.jar
```

## 项目结构

```text
src/main/java/com/agent/codereviewagent/
├── config/       Spring AI、HTTP 客户端和 Agent 配置
├── controller/   /ai 下的对话接口
├── models/dto/   Agent 请求与响应对象
└── tool/         Agent 可调用的课程与时间工具
```

## 注意事项

- 请不要把真实的 `OPENAI_API_KEY` 提交到仓库。
- 当前会话记忆使用 `MemorySaver`，仅适合示例和本地开发，不适合生产环境。
- 应用将模型调用超时设置为 120 秒，并关闭自动重试，以避免上游错误导致请求长时间阻塞。
