# toys-video

[![Java 21](https://img.shields.io/badge/Java-21-orange)]() [![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green)]() [![Spring Cloud Alibaba](https://img.shields.io/badge/Spring%20Cloud%20Alibaba-2025.0.0.0-red)]() [![License: MIT](https://img.shields.io/badge/License-MIT-blue)]()

> 微服务视频平台学习项目:上传(分片直传/秒传/断点续传)→ 风控评级(类型化违规/黑样本/敏感词热更新)→ 人工审核(认领)→ 转码(HLS)→ 播放(Artplayer 弹幕)→ 互动(点赞/收藏/评论/弹幕/关注/处罚)。6 服务 + 69 单测 + 22 步全链路回归。

微服务架构的视频平台 MVP(学习项目):**上传 → 自动机审 → 人工审核 → 转码 → 播放** 全链路。

参考 Bilibili/YouTube 的核心链路,重点在微服务整套流程的实践:服务注册与配置中心(Nacos)、网关统一鉴权(Spring Cloud Gateway + JWT)、服务间调用(OpenFeign)、异步事件驱动(RocketMQ)、对象存储(MinIO/S3)、schema-per-service(PostgreSQL)。

## 架构

```
前端 Vue3 + Vite + Element Plus + hls.js
        │
   Gateway :8080 ── 路由 / JWT 校验 / CORS / traceId / 统一错误兜底
        │
 user:8101     video:8102      moderation:8103    media:8104
 注册登录JWT    上传/状态机/播放   机审+人工审核       转码 worker
        │              │               │                │
        └── PostgreSQL(schema-per-service) ── MinIO(videos/hls) ── RocketMQ 事件 ── Redis ──┘
```

- 状态机唯一写入方是 video-service;机审/转码服务只通过内部接口请求推进,事件只做触发。
- 状态流转:`UPLOADED → AUTO_SCREENING → UNDER_REVIEW → APPROVED → TRANSCODING → PUBLISHED`,分支 `REJECTED` / `TRANSCODE_FAILED`(投稿人可重试)。
- 转码产物为多码率 HLS(master.m3u8 + 480p/720p/1080p)+ 封面,经网关 `/media/**` 代理播放。

## 目录

| 目录 | 内容 |
|---|---|
| `toys-common` | 统一响应/错误码、全局异常、traceId、JWT、雪花 ID、Python 脚本执行器 |
| `toys-api` | 跨服务契约:Feign 接口、事件 topic 与 DTO |
| `toys-gateway` | 网关:路由、鉴权过滤器、CORS、错误出口 |
| `toys-user-service` | 注册/登录/用户信息 |
| `toys-video-service` | 上传(MinIO 流式落盘)、状态机、列表/详情、播放量(Redis 缓冲)、搜推扩展点 |
| `toys-moderation-service` | 机审(FFprobe+抽帧规则)、人工审核后台 API |
| `toys-media-service` | 转码 worker(虚拟线程、重试 3 次、优雅停机) |
| `frontend/` | Vue3 单页应用(首页/播放/投稿/我的/审核后台/登录注册) |
| `scripts/` | Python 媒体脚本(纯标准库):probe_media.py、auto_screen.py、transcode.py |
| `deploy/` | docker-compose、broker.conf、启动脚本、运维手册 |
| `docs/` | 设计文档、实施计划 |

## 快速开始

环境要求:Java 21、Maven、Node 22、Python 3、ffmpeg、Docker(中间件)、PostgreSQL。

完整启动步骤、账号、验收清单、故障演练见 **[deploy/README.md](deploy/README.md)**。

```bash
# 中间件
cd deploy && docker compose up -d && cd .. && ./deploy/start-minio.sh &

# 后端
export JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home
mvn -s deploy/maven-settings.xml -DskipTests package
for svc in user-service gateway video-service moderation-service media-service; do
  java -jar toys-$svc/target/toys-$svc-0.1.0-SNAPSHOT.jar &
done

# 前端
cd frontend && npm install && npm run dev   # http://localhost:5173
```

内置管理员:`admin / admin123`。

## 设计文档

- 设计规格:[docs/superpowers/specs/2026-09-25-video-platform-microservices-design.md](docs/superpowers/specs/2026-09-25-video-platform-microservices-design.md)
- 实施计划:[docs/superpowers/plans/2026-09-25-video-platform-microservices.md](docs/superpowers/plans/2026-09-25-video-platform-microservices.md)

## 分支模型

- `main`:稳定分支,保持 CI 绿色(每次 push 自动跑单测+构建)
- `dev`:日常开发分支,功能完成后合回 main
- feature 分支:`feat/xxx` 从 dev 切出,完成后 PR → dev → dev 稳定后 PR → main

```bash
git checkout dev && git pull
git checkout -b feat/your-feature   # 开发
# ...提交后
git push -u origin feat/your-feature  # GitHub 上发 PR 合入 dev
```
