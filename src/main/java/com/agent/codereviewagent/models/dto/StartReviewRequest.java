package com.agent.codereviewagent.models.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/** 编号始终相对于路径中的被审查仓库。 */
public record StartReviewRequest(@JsonDeserialize(using = NumberDeserializer.class) Long number) {
    /** 只约束此请求字段，拒绝 Jackson 默认的小数截断及字符串强制转换。 */
    public static final class NumberDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "PR/MR 编号必须是正整数");
            }
            long value = parser.getLongValue();
            if (value <= 0) { return context.reportInputMismatch(Long.class, "PR/MR 编号必须是正整数"); }
            return value;
        }
    }
}
