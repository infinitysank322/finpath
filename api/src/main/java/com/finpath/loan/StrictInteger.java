package com.finpath.loan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Accepts only a JSON integer: a string, decimal or boolean is a 400, never coerced. Used for paise and counts. */
final class StrictInteger extends JsonDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Long) ctxt.handleUnexpectedToken(Long.class, p);
        }
        return p.getLongValue();
    }
}
