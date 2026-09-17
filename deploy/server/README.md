# 服务器部署手册（Smartledge）

面向 Ubuntu 22.04/24.04 单机部署：Docker 依赖栈 + Java 业务服务 + Python 算法服务 + nginx 静态站点与反代。

线上实例：阿里云香港 8 vCPU / 16 GB，站点 https://smartledge.cn （`smartlect.cn` 301 到主域名）。

## 1. 目录约定

| 路径 | 用途 |
| --- | --- |
| `/srv/smartledge/app` | 仓库源码（Java 从仓库根启动以读取 `.env`） |
| `/srv/smartledge/app/.env` | 全部凭据与端口（`chmod 600`，来自 `.env.example` 模板） |
| `/srv/smartledge/release/smartledge-business-chat.jar` | 后端可执行 jar |
| `/srv/smartledge/release/web` | 前端构建产物（nginx 站点根） |
| `/srv/smartledge/logs` | systemd 服务的 stdout/stderr 日志 |

## 2. 依赖安装

```bash
apt-get update
apt-get install -y docker.io docker-compose-v2 nginx certbot python3-certbot-nginx rsync
systemctl enable --now docker nginx
```

## 3. 依赖栈

```bash
# 1) ES + IK 镜像（官方镜像不含中文分词插件）
docker build -t smartledge-elasticsearch:8.18.6-ik /srv/smartledge/app/deploy/elasticsearch

# 2) 拉起全部中间件（首次启动会自动执行 sql/ 下的建库、建表、迁移与种子脚本）
cd /srv/smartledge/app
docker compose --env-file .env -f deploy/docker-compose.yml up -d
docker compose --env-file .env -f deploy/docker-compose.yml ps
```

首次初始化需要 1-2 分钟；`mysql` 与 `elasticsearch` 的 healthcheck 转 healthy 后即可继续。
数据库建好后核对一次中文种子（曾出现过 CLI 写入的双重编码问题）：

```bash
docker exec smartledge-mysql mysql -uroot -p"$SMARTLEDGE_MYSQL_PASSWORD" -e \
  "USE smartledge; SELECT tenant_id, tenant_name FROM smartledge_tenant;"
```

## 4. Python 算法服务

需要 Python 3.11 与 `uv`：

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
cd /srv/smartledge/app/smartledge-rag-tools
uv venv --python 3.11 .venv
uv pip install --python .venv/bin/python -r requirements.txt
```

模型为懒加载，首次请求时会从 HuggingFace 下载（约 4.6 GB：`BAAI/bge-m3` 与 `BAAI/bge-reranker-v2-m3`）。
国内/离线环境可先设置 `HF_ENDPOINT=https://hf-mirror.com`，或把本机 `~/.cache/huggingface` 上传到服务器同一路径。

## 5. 服务化

```bash
cp /srv/smartledge/app/deploy/server/smartledge-app.service /etc/systemd/system/
cp /srv/smartledge/app/deploy/server/smartledge-rag-tools.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now smartledge-rag-tools smartledge-app
systemctl status smartledge-app --no-pager
```

## 6. nginx 与证书

```bash
mkdir -p /var/www/html
cp /srv/smartledge/app/deploy/server/nginx-smartledge.conf /etc/nginx/sites-available/smartledge.conf
ln -sf /etc/nginx/sites-available/smartledge.conf /etc/nginx/sites-enabled/smartledge.conf
rm -f /etc/nginx/sites-enabled/default
nginx -t

# 先用 HTTP 配置通过 ACME 校验并把证书落到 /etc/letsencrypt/live/smartledge.cn/
certbot certonly --webroot -w /var/www/html \
  -d smartledge.cn -d www.smartledge.cn -d smartlect.cn -d www.smartlect.cn \
  --agree-tos -m you@example.com --non-interactive

nginx -t && systemctl reload nginx
```

证书自动续期由 `certbot.timer` 负责，续期后需要重载 nginx：

```bash
cat > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh <<'SH'
#!/usr/bin/env bash
systemctl reload nginx
SH
chmod +x /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
```

## 7. 访问限制

`nginx-smartledge.conf` 内置两档限流（按 IP）：

| 入口 | 速率 | 突发 |
| --- | --- | --- |
| `POST /api/chat/stream` | 20 次/分钟 | 5 |
| 其他 `/api`、`/manage`、`/admin` | 300 次/分钟 | 60 |

公网只暴露 80/443，所有中间件端口都绑定在 `127.0.0.1`。安全组只放行 80/443，SSH 端口按来源 IP 单独放行。

## 8. 升级流程

```bash
# 后端
cd /srv/smartledge/app && git pull
mvn -DskipTests package
cp smartledge-business/smartledge-business-chat/target/smartledge-business-chat-0.0.1-SNAPSHOT.jar \
   /srv/smartledge/release/smartledge-business-chat.jar
systemctl restart smartledge-app

# 前端
cd vue && npm ci && npm run build
rsync -a --delete dist/ /srv/smartledge/release/web/

# Python
systemctl restart smartledge-rag-tools
```
