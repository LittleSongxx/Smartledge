package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.ChatCallOptions;

/** Minimal model invocation contract consumed by manage-side enrichment advisors. */
public interface ObservedChatModelPort {

    String callText(String stageName, String systemPrompt, String userPrompt);

    String callText(String stageName,
                    String systemPrompt,
                    String userPrompt,
                    ChatCallOptions callOptions,
                    ModelObservationSink traceSink);
}
