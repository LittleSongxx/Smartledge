# Elasticsearch + IK 中文分词插件。
#
# 关键词通道、导航索引与知识路由索引都按中文分词建库，官方镜像不含 IK，需要自带。
# 构建：docker build -t smartledge-elasticsearch:8.18.6-ik deploy/elasticsearch
#
# 插件版本必须与 ES 版本严格一致，升级 ES 时同步改这里的两处版本号。
FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.6

ARG IK_VERSION=8.18.6

# 优先官方分发地址，失败时回退到 GitHub release 同版本包。
RUN bin/elasticsearch-plugin install --batch \
        "https://get.infini.cloud/elasticsearch/analysis-ik/${IK_VERSION}" \
    || bin/elasticsearch-plugin install --batch \
        "https://github.com/infinilabs/analysis-ik/releases/download/v${IK_VERSION}/elasticsearch-analysis-ik-${IK_VERSION}.zip"
