package com.agent.codereviewagent.models.dto;

import java.util.List;

/** 只接收仓库网页 URL 和文档定位；不接收平台 API 地址、仓库数字身份或凭据。 */
public record RepositoryRequest(String url, List<String> standardPaths, List<String> specPaths) {}
