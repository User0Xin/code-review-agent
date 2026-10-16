package com.agent.codereviewagent.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultTest {

    @Test
    void successWithoutDataUsesSuccessCode() {
        Result<Void> result = Result.success();

        assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS);
        assertThat(result.getData()).isNull();
        assertThat(result.getMsg()).isNull();
    }

    @Test
    void successCarriesThePayload() {
        Result<String> result = Result.success("ok");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo("ok");
    }

    @Test
    void errorUsesTheDefaultErrorCode() {
        Result<Void> result = Result.error("失败");

        assertThat(result.getCode()).isEqualTo(ResultCode.ERROR);
        assertThat(result.getMsg()).isEqualTo("失败");
        assertThat(result.getData()).isNull();
    }

    @Test
    void errorCanUseACallerSuppliedCode() {
        Result<Void> result = Result.error(400, "参数不对");

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMsg()).isEqualTo("参数不对");
    }

    @Test
    void pageResultFitsInsideResultData() {
        PageResult<String> page = new PageResult<>(2, List.of("a", "b"));
        Result<PageResult<String>> result = Result.success(page);

        assertThat(result.getData().getTotalNum()).isEqualTo(2);
        assertThat(result.getData().getPage()).containsExactly("a", "b");
    }
}
