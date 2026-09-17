package org.smartledge.ai.rag.runtime.config;

import org.smartledge.ai.rag.runtime.port.ChatRuntimeConfigProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @description: 配置属性
 * @author: Song
 **/
@ConfigurationProperties(prefix = "app.chat")
public class ChatAgentProperties {

    @Autowired(required = false)
    private ChatRuntimeConfigProvider runtimeConfigProvider;

    private boolean recommendationEnabled = true;
    private int maxModelCallsPerRun = 8;
    private int maxModelCallsPerThread = 40;
    private int maxToolCallsPerRun = 6;
    private int maxToolCallsPerThread = 30;
    private int historyPreviewTurns = 4;
    private long recommendationTimeoutMs = 3000L;

    public boolean isRecommendationEnabled() {
        return managed().recommendationEnabled;
    }

    public void setRecommendationEnabled(boolean recommendationEnabled) {
        this.recommendationEnabled = recommendationEnabled;
    }

    public int getMaxModelCallsPerRun() {
        return managed().maxModelCallsPerRun;
    }

    public void setMaxModelCallsPerRun(int maxModelCallsPerRun) {
        this.maxModelCallsPerRun = maxModelCallsPerRun;
    }

    public int getMaxModelCallsPerThread() {
        return managed().maxModelCallsPerThread;
    }

    public void setMaxModelCallsPerThread(int maxModelCallsPerThread) {
        this.maxModelCallsPerThread = maxModelCallsPerThread;
    }

    public int getMaxToolCallsPerRun() {
        return managed().maxToolCallsPerRun;
    }

    public void setMaxToolCallsPerRun(int maxToolCallsPerRun) {
        this.maxToolCallsPerRun = maxToolCallsPerRun;
    }

    public int getMaxToolCallsPerThread() {
        return managed().maxToolCallsPerThread;
    }

    public void setMaxToolCallsPerThread(int maxToolCallsPerThread) {
        this.maxToolCallsPerThread = maxToolCallsPerThread;
    }

    public int getHistoryPreviewTurns() {
        return managed().historyPreviewTurns;
    }

    public void setHistoryPreviewTurns(int historyPreviewTurns) {
        this.historyPreviewTurns = historyPreviewTurns;
    }

    public long getRecommendationTimeoutMs() {
        return managed().recommendationTimeoutMs;
    }

    public void setRecommendationTimeoutMs(long recommendationTimeoutMs) {
        this.recommendationTimeoutMs = recommendationTimeoutMs;
    }

    public record Limits(int modelRun, int modelThread, int toolRun, int toolThread) { }

    public Limits snapshot() {
        ChatAgentProperties value = managed();
        return new Limits(value.maxModelCallsPerRun, value.maxModelCallsPerThread,
            value.maxToolCallsPerRun, value.maxToolCallsPerThread);
    }

    private ChatAgentProperties managed() {
        if (runtimeConfigProvider == null) {
            return this;
        }
        ChatAgentProperties managed = runtimeConfigProvider.currentChat();
        return managed == null || managed == this ? this : managed;
    }
}
