package org.smartledge.ai.ragtools.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagToolsEmbedRequest {

    private List<String> texts;
}
