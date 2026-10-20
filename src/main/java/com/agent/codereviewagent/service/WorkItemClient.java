package com.agent.codereviewagent.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 按工作项 ID 读取纯文本描述。找不到时返回空，不表示接口失败。 */
public interface WorkItemClient {

    Optional<String> readPlainText(String workItemId);

    @Component
    class EmptyWorkItemClient implements WorkItemClient {

        @Override
        public Optional<String> readPlainText(String workItemId) {
            return Optional.empty();
        }
    }
}
