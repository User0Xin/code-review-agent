package com.agent.codereviewagent.service;

import java.util.Optional;

/** 按工作项 ID 读取纯文本描述。找不到时返回空，不表示接口失败。 */
public interface WorkItemClient {

    Optional<String> readPlainText(String workItemId);

}
