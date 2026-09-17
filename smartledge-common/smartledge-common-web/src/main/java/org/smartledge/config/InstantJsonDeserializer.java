package org.smartledge.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;

/**
 * @description: 配置类
 * @author: Song
 **/

public class InstantJsonDeserializer extends JsonDeserializer<Instant> {

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        Date convertDate = DateJsonDeserializer.parseDate(p.getText());
        return convertDate != null ? convertDate.toInstant() : null;
    }
}
