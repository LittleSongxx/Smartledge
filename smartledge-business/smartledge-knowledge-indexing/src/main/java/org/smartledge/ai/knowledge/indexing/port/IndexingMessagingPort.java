package org.smartledge.ai.knowledge.indexing.port;

/**
 * 文档索引链路的队列配置接缝。
 *
 * <p>由消费方模块拥有，宿主应用实现。这里只承载<b>基础队列名</b>，不包含资源前缀；
 * 前缀的拼接由 {@code DocumentMessagingTopology} 唯一完成，避免生产端、消费端和拓扑声明
 * 各自计算名字而出现不一致。</p>
 */
@FunctionalInterface
public interface IndexingMessagingPort {

    MessagingQueues currentQueues();

    record MessagingQueues(String parseRouteQueue, String indexBuildQueue) {

        private static final String DEFAULT_PARSE_ROUTE_QUEUE = "document-parse-route";

        private static final String DEFAULT_INDEX_BUILD_QUEUE = "document-index-build";

        public MessagingQueues {
            parseRouteQueue = normalize(parseRouteQueue, DEFAULT_PARSE_ROUTE_QUEUE);
            indexBuildQueue = normalize(indexBuildQueue, DEFAULT_INDEX_BUILD_QUEUE);
        }

        public static MessagingQueues defaults() {
            return new MessagingQueues(DEFAULT_PARSE_ROUTE_QUEUE, DEFAULT_INDEX_BUILD_QUEUE);
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
