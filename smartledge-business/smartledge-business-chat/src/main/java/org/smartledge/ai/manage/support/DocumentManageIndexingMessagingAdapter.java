package org.smartledge.ai.manage.support;

import org.smartledge.ai.knowledge.indexing.port.IndexingMessagingPort;
import org.smartledge.ai.manage.config.DocumentManageProperties;
import org.springframework.stereotype.Component;

/**
 * 组合根适配器：把管理侧属性投影为索引链路的消息接缝。
 *
 * <p>只投影基础队列名，不含资源前缀。前缀由 {@code DocumentMessagingTopology} 统一拼接。</p>
 */
@Component
public final class DocumentManageIndexingMessagingAdapter implements IndexingMessagingPort {

    private final DocumentManageProperties properties;

    public DocumentManageIndexingMessagingAdapter(DocumentManageProperties properties) {
        this.properties = properties == null ? new DocumentManageProperties() : properties;
    }

    @Override
    public MessagingQueues currentQueues() {
        DocumentManageProperties.Messaging messaging = properties.getMessaging();
        if (messaging == null) {
            return MessagingQueues.defaults();
        }
        return new MessagingQueues(messaging.getParseRouteQueue(), messaging.getIndexBuildQueue());
    }
}
