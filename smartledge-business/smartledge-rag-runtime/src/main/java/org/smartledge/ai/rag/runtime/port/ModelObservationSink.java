package org.smartledge.ai.rag.runtime.port;

import org.smartledge.ai.rag.runtime.model.ChatModelUsageTrace;

@FunctionalInterface
public interface ModelObservationSink {
    void addModelUsageTrace(ChatModelUsageTrace trace);
}
