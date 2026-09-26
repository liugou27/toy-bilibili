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
# 1. 中间件(docker compose:仅启动中间件,服务在宿主机直跑)
cd deploy && docker compose up -d nacos rocketmq-namesrv rocketmq-broker redis && cd ..
# 注意:compose 中 broker 挂载默认指向 broker-docker.conf(容器模式),
# 宿主机直跑服务需把挂载换回 ./rocketmq/broker.conf(brokerIP1=127.0.0.1),否则服务连不上 broker

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

## 全栈容器化

除 MinIO 外,PostgreSQL、5 个 Java 服务与前端也定义在 `deploy/docker-compose.yml`,一条命令拉起整套系统。

### 构建与启动

```bash
# 1. 先在宿主机完成编译:容器构建只 COPY 既有 jar,不在镜像内编译
mvn -s deploy/maven-settings.xml -DskipTests package

# 2. 构建镜像并启动(Java 服务共用 deploy/docker/Dockerfile.service 模板,
#    以 build-arg 传入 JAR_FILE 与 PORT;前端为 deploy/docker/Dockerfile.frontend 多阶段构建)
cd deploy && docker compose build && docker compose up -d
```

启动完成后浏览器访问 **http://localhost:8081**(前端 nginx 容器,80 映射到宿主 8081,反代 compose 网络内的 gateway:8080,同源无跨域)。中间件(nacos/rocketmq/redis)的宿主端口映射沿用原配置。

容器模式各服务的连接目标由环境变量注入(NACOS_ADDR=nacos:8848、DB_HOST=postgres、DB_PORT=5432、REDIS_HOST=redis、ROCKETMQ_ADDR=rocketmq-namesrv:9876、MINIO_ENDPOINT=http://host.docker.internal:9000),application.yml 中占位符默认值仍指向 127.0.0.1,宿主机直跑零改动。

### PostgreSQL(容器)

- postgres:16-alpine,库 `toys_video`,四个 schema 由 `deploy/docker/init-db.sql` 自动创建。
- 端口不映射到宿主,避免与本机 5433 独立集群冲突,仅 compose 网络内访问。
- 认证方式 trust,与宿主机开发集群一致(yml 默认空密码可直接连)。

### 两套 broker.conf

| 文件 | brokerIP1 | 适用模式 |
|---|---|---|
| `deploy/rocketmq/broker.conf` | 127.0.0.1 | 宿主机直跑服务 |
| `deploy/rocketmq/broker-docker.conf` | rocketmq-broker | 全栈容器化(compose 默认挂载) |

Broker 把 brokerIP1 作为自身地址上报给客户端:宿主机客户端只能连 127.0.0.1,容器内客户端只能按服务名连,二者互不兼容,故按模式选用。

### MinIO 保持宿主机进程

官方自 2025 年起停止发布 MinIO 容器镜像,arm64 无官方镜像可用,继续以宿主机进程运行(`deploy/start-minio.sh`,9000/9001)。容器内服务与网关经 `host.docker.internal:9000` 访问,compose 中已配置 `extra_hosts: host.docker.internal:host-gateway`。

### 已知限制

- **broker 容器模式下,宿主机直跑服务连不上 broker**:brokerIP1=rocketmq-broker 在宿主机不可解析。混合模式(容器中间件 + 宿主机服务)需把 compose 中 broker 挂载换回 `./rocketmq/broker.conf`。
- **moderation/media 容器内无法执行机审与转码脚本**:基础镜像 eclipse-temurin:21-jre 仅含 JRE,scripts/ 已只读挂载到 /app/scripts,但脚本运行依赖的 python3 与 ffmpeg 不在镜像内。

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
