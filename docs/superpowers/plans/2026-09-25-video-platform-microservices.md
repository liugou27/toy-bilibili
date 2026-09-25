# toys-video 微服务视频平台实施计划

> **For agentic workers:** 本计划由会话内自主执行(用户已授权独立完成),按阶段批量推进,每阶段结束做编译与运行验证并提交。

**Goal:** 实现 5 服务微服务视频平台 MVP(上传→机审→人工审核→转码→播放),全链路真实可跑。

**Architecture:** Spring Cloud Alibaba(Nacos 注册+配置、Gateway、OpenFeign)+ RocketMQ 异步事件 + MinIO 对象存储 + 本机 PostgreSQL(schema-per-service)+ Vue3 前端 + Python 媒体脚本。状态机唯一写入方 video-service,事件只做触发。

**Tech Stack:** Java 21 / Spring Boot 3.5.x / SCA / MyBatis-Plus / Flyway / jjwt / MinIO SDK / Vue3+Vite+ElementPlus+hls.js / Python3 stdlib。

## Global Constraints

- 构建统一 `JAVA_HOME=/Users/dsy/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home`。
- 包根 `com.toys.video`;模块 artifactId `toys-*`。
- 服务端口:gateway 8080、user 8101、video 8102、moderation 8103、media 8104。
- 中间件:Nacos 8848、RocketMQ namesrv 9876(brokerIP1=127.0.0.1)、Redis 6379、MinIO 9000/9001。
- MinIO bucket:`videos`(私有,原片)、`hls`(公开读,转码产物+封面);服务启动幂等初始化。
- 事件 topic:`VIDEO_UPLOADED`、`VIDEO_APPROVED`(toys-api 定义常量+DTO)。
- 统一响应 `{code,message,data}`;内部接口 `/internal/**` 网关拒绝。
- 状态机:`UPLOADED→AUTO_SCREENING→UNDER_REVIEW→APPROVED→TRANSCODING→PUBLISHED`;`REJECTED`、`TRANSCODE_FAILED` 终态。
- 上传限制:mp4/mkv/mov/avi/flv,≤2GB;标题≤100 字,简介≤2000 字。
- 用户提供 PG 密码前,先用本机 PG 尝试 trust 连接;不行则在 5433 端口 initdb 独立集群(dsy 用户,trust),不阻塞开发。

## File Structure

```
pom.xml                                父 POM
toys-common/src/main/java/.../common/  R(响应)、错误码、BizException、traceId 工具、雪花ID
toys-api/src/main/java/.../api/        topic 常量、事件DTO、Feign 接口(VideoInternalClient等)
toys-gateway/                          路由、JWT 全局过滤器、CORS、/internal 拦截
toys-user-service/                     AuthController、UserController、User、Flyway V1
toys-video-service/                    VideoController(上传/列表/详情/play)、Video、状态机Service、
                                       MinIO 基础设施(BucketInitializer)、事件发布、播放量(Redis缓冲)
toys-moderation-service/               事件消费、ModerationService(调Python)、AdminController(审核API)、Flyway V1
toys-media-service/                    事件消费、TranscodeService(调Python)、Feign回写、虚拟线程、Flyway V1
frontend/src/                          views(Home/Watch/Upload/Login/Register/My/AdminReview)、
                                       router、api(axios封装)、stores(auth)
scripts/                               probe_media.py、auto_screen.py、transcode.py
deploy/                                docker-compose.yml、rocketmq/broker.conf、init-sql/、README.md
```

## Tasks(按阶段)

### 阶段一:骨架 + 基础设施 + 网关 + 用户服务
1. 父 POM + 7 模块空壳(依赖管理:Boot/SCA BOM、MyBatis-Plus、jjwt、minio、rocketmq-spring)。验证:`mvn -q validate`。
2. deploy/docker-compose.yml + broker.conf + README(含 Nacos 导入配置说明)。验证:`docker compose up -d` 后 nacos/rocketmq/redis/minio 健康。
3. toys-common:R、ErrorCode、BizException、全局异常处理、TraceIdFilter(MDC)、Snowflake、JwtUtil(签发/校验,共享 secret 配置)。
4. toys-gateway:路由(api/{user,video,moderation}→服务 lb;media→minio 代理)、全局 JWT 过滤器(白名单:/api/auth/**、/api/videos GET 列表/详情、/media/**)、透传 X-User-Id/X-User-Role、/internal/** 403、CORS(配置开关)。
5. toys-user-service:Flyway 建 users+种子 admin;注册/登录/me 接口。验证:经网关注册→登录→me 200;错误密码 401 语义。
6. PG 准备:按 Global Constraints 策略创建 4 个 schema 及测试 schema;连接配置写 Nacos(common.yml)。验证:服务启动读 Nacos 配置成功、注册进 Nacos 服务列表。

### 阶段二:视频服务
7. video-service:Flyway videos 表;MinIO 配置+BucketInitializer;上传接口(流式 putObject,校验类型/大小,雪花 videoId);VIDEO_UPLOADED 事件发布(状态置 AUTO_SCREENING 后发);列表/详情/我的投稿/播放量缓冲接口。验证:curl 上传样例视频→MinIO 控制台见对象→列表/详情 200。
8. 单测:状态机合法流转表、JwtUtil、上传校验。验证:`mvn test` 绿。

### 阶段三:审核服务
9. scripts/probe_media.py、auto_screen.py(契约见设计文档§13)+ ffmpeg 合成样例视频自测。
10. moderation-service:消费 VIDEO_UPLOADED(幂等查状态)→拉原片→机审→写报告→Feign 回写(UNDER_REVIEW/REJECTED);审核 API:队列/详情(presigned URL)/approve(触发 VIDEO_APPROVED)/reject。验证:上传后 30s 内状态到 UNDER_REVIEW;approve 后 video-service 状态 APPROVED 且收到事件。
11. 前端:骨架(Vite+路由+axios+auth store)+ 登录/注册/我的投稿/审核后台页。验证:`npm run build` 成功;浏览器走通注册→登录→审核驳回。

### 阶段四:转码 + 播放 + 前端完整化
12. scripts/transcode.py + 自测。
13. media-service:消费 VIDEO_APPROVED→Feign 置 TRANSCODING→transcode_jobs 记录→拉原片→转码→产物回传→PUBLISHED;失败重试 3 次;虚拟线程;优雅停机。验证:上传→审核通过→约 1 分钟后 PUBLISHED,MinIO 见 master.m3u8。
14. 前端:首页卡片流、播放页(hls.js+错误态+重试)、上传页(进度条)。验证:全链路浏览器走通,视频可播放、可拖进度。
15. 健壮性收尾:actuator、网关 503 兜底、消费幂等确认、孤儿临时目录清理、优雅停机。验证:杀 media-service 重启任务续跑;网关停 video 服务时 /api/videos 返回统一 503 JSON。

### 阶段五:交付
16. deploy/README(启动顺序、账号、端口表、故障演练)、根 README;全量提交;E2E 手工清单执行记录。

## Self-Review

- 设计文档§5 服务职责↔任务 4/5/7/10/13 一一对应;§7 链路↔7/10/13;§9 API↔5/7/10/14;§12 六页↔11/14;§16 跨域↔4(网关CORS)+16 README;§17 健壮性↔8/15。无缺口。
- 类型一致性:事件 payload 字段(videoId/objectKey/ownerId)与 Feign 接口方法签名在各任务间已统一,以 toys-api 定义为准。
- 无占位符;步骤均有验证命令。
