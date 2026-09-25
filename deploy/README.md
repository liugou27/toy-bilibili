# toys-video 部署与运维手册

微服务视频平台 MVP(学习项目)。架构与设计见 `docs/superpowers/specs/2026-09-25-video-platform-microservices-design.md`。

## 组件与端口

| 组件 | 地址 | 说明 |
|---|---|---|
| 前端(Vite dev) | http://localhost:5173 | 代理 /api、/media 到网关 |
| Gateway | http://localhost:8080 | 统一入口 |
| user-service | :8101 | 注册/登录/JWT |
| video-service | :8102 | 上传/状态机/播放页 API |
| moderation-service | :8103 | 机审 + 人工审核 API |
| media-service | :8104 | 转码 worker |
| Nacos | :8848(gRPC :9848) | 注册+配置中心,控制台 http://localhost:18080 |
| RocketMQ NameServer / Broker | :9876 / :10911 | 异步事件 |
| Redis | :6379 | 播放量缓冲 |
| MinIO | :9000(API)/ :9001(控制台) | 对象存储,本机进程(非容器) |
| PostgreSQL | :5433,库 `toys_video` | 独立集群;4 个 schema 对应 4 个服务 |

## 启动顺序

```bash
# 1. 中间件(docker compose:nacos/rocketmq/redis)
cd deploy && docker compose up -d && cd ..

# 2. MinIO(官方已无 darwin-arm64 构建,用 Rosetta 转译 amd64 二进制)
deploy/start-minio.sh &          # 数据目录 ~/toys-minio-data,账号 minioadmin/minioadmin

# 3. PostgreSQL(首次)
/Library/PostgreSQL/18/bin/initdb -D ~/pgdata/toys-video -U postgres --auth-local=trust --auth-host=trust
/Library/PostgreSQL/18/bin/pg_ctl -D ~/pgdata/toys-video -o "-p 5433" -l ~/pgdata/toys-video.log start
/Library/PostgreSQL/18/bin/psql -p 5433 -U postgres -c "create database toys_video;"
/Library/PostgreSQL/18/bin/psql -p 5433 -U postgres -d toys_video \
  -c "create schema user_db; create schema video_db; create schema moderation_db; create schema media_db;"

# 4. 后端服务(JAVA_HOME 必须指向 Java 21)
export JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home
mvn -s deploy/maven-settings.xml -DskipTests package
for svc in user-service gateway video-service moderation-service media-service; do
  java -jar toys-$svc/target/toys-$svc-0.1.0-SNAPSHOT.jar &
done
# 注意:先停服务再重新 package;运行中覆盖 jar 会导致懒加载类崩溃

# 5. 前端
cd frontend && npm install && npm run dev
```

Maven 用项目级配置 `deploy/maven-settings.xml`(阿里云 HTTPS + 本机代理),绕开全局 settings 里的内网 Nexus。

## 内置账号

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | ADMIN(服务启动时自动种子) |
| 自行注册 | - | USER |

## 端到端手工验收清单

1. 浏览器打开 http://localhost:5173,注册账号并登录。
2. 「投稿」上传 mp4(≤2GB),填写标题,观察进度条;完成后自动跳「我的投稿」。
3. 「我的投稿」状态自动轮询:机审中 → 等待人工审核。
4. 用 admin 登录 → 「审核后台」:查看机审报告(检查项表格)、播放原片(presigned URL)。
5. 「通过并发布」→ 「我的投稿」状态变为:审核通过 → 转码中 → 已发布。
6. 首页出现该视频;播放页 hls.js 播放,可拖进度条(Range 请求)。
7. 上传纯黑/损坏视频 → 机审 AUTO_FAIL → 自动未通过,不进人工队列。

## 故障演练

- **转码 worker 崩溃恢复**:kill media-service 后重新启动,进行中的转码消息由 RocketMQ 重投递继续处理(作业表 attempts 记录重试次数,上限 3)。
- **转码重试用尽**:作业 FAILED 终态,视频状态 TRANSCODE_FAILED,投稿人可在「我的投稿」点「重试转码」。
- **下游不可用**:停掉 video-service,经网关访问 /api/videos 返回结构化 `{"code":5001,...}` 而非连接错误裸页。
- **机审消息重放**:消费失败抛异常,消息进重试队列(延迟递增),16 次后进死信 `%DLQ%toys-moderation-consumer`。

## Nacos 配置中心

namespace `public`(学习项目),data-id:`common.yml`、`{service}.yml`。本地 application.yml 中的配置为兜底默认值,Nacos 同名配置优先。敏感配置(PG 密码、MinIO 密钥、JWT secret)生产环境应放 Nacos 并开启鉴权。

## 已知适配(本机环境)

- **MinIO 官方 2025 年起停止 Docker Hub/brew 发布**,本机用 GitHub Releases 的 darwin-amd64 二进制 + Rosetta 转译运行(`deploy/start-minio.sh`)。
- **Nacos 3.0.3 镜像**要求设置 `NACOS_AUTH_TOKEN`(compose 中已配置,auth 关闭仅用于本地)。
- **RocketMQ Broker** 容器内需 `brokerIP1=127.0.0.1`(见 `deploy/rocketmq/broker.conf`),否则宿主机客户端拿到容器 IP 连不上。
