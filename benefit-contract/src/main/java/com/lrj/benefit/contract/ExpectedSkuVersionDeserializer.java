package com.lrj.benefit.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** 新版本前提只接受JSON整数，不能把小数截断或字符串强转后冒充冻结版本。 */
public final class ExpectedSkuVersionDeserializer extends JsonDeserializer<Long> {
    /** 仅约束新增字段，不改变旧quantity等字段的历史JSON行为；显式null仍由Jackson保留。 */
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        return parser.getLongValue();
    }
}
