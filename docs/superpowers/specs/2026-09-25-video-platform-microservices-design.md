# toys-video 视频平台微服务 MVP 设计

日期:2026-09-25
状态:已与需求方确认批准

## 1. 背景与目标

从零搭建一个类 YouTube/Bilibili 的视频网站,以**学习微服务完整架构**为主要目标,业务链路作为载体。MVP 覆盖核心链路:**上传 → 内容审核 → 转码 → 播放**。架构设计面向后续扩展(搜推、CDN、更多业务),但不提前实现。

### 成功标准

1. `docker compose up` 一键拉起全部中间件。
2. 五个服务启动并注册到 Nacos,配置从 Nacos 配置中心读取。
3. 全链路手动验收:注册 → 登录 → 上传 → 自动机审 → 管理员通过 → 转码完成 → 首页可见 → 播放页流畅播放 HLS。
4. 故障演练:杀掉 media-service 后重启,未完成的转码任务可继续处理。

## 2. 环境基线(本机已具备)

| 组件 | 状态 |
|---|---|
| Java | Microsoft OpenJDK 21.0.12,位于 `~/Library/Java/JavaVirtualMachines/ms-21.0.12`(默认 `java` 是 8,构建/运行需 `JAVA_HOME` 指向 21) |
| Maven | 3.9.16 |
| PostgreSQL | 18,EDB 原生安装于 `/Library/PostgreSQL/18`,服务运行中(5432),bin 不在 PATH;超级用户(postgres)密码由需求方在实施时提供 |
| ffmpeg/ffprobe | 8.1.2(brew) |
| Node/npm | 22.23.1 / 10.9.8 |
| Python | 3.13.14 |
| Docker | Docker Desktop 4.92.0(Engine 29.8.0,arm64),镜像拉取走系统代理 127.0.0.1:7897 |
| 网络 | 本机代理 127.0.0.1:7897,Docker/brew/curl 需显式配置时使用 |

## 3. 技术栈与版本

- Java 21(media-service 开启虚拟线程)
- Spring Boot 3.5.x + Spring Cloud 2025.0.x + Spring Cloud Alibaba 对应版本(实现时以 SCA 官方兼容矩阵校准)
- Spring Cloud Gateway、OpenFeign、Nacos Discovery/Config、Sentinel(阶段五)
- RocketMQ 5.x(rocketmq-spring-boot-starter)
- MyBatis-Plus + Flyway
- jjwt 0.12.x + Spring Security(仅 user-service 做认证,其余服务网关透传身份)
- MinIO Java SDK 8.x
- 中间件镜像(均原生支持 arm64):Nacos 3.0.3 standalone、RocketMQ 5.3.x(NameServer+Broker)、Redis 7.4、MinIO(见部署说明)
- 前端:Vue 3 + Vite + Element Plus + hls.js + axios + vue-router
- Python 脚本:仅标准库(subprocess 调 ffmpeg/ffprobe),无第三方依赖

## 4. 总体架构

```
前端 Vue3 (Vite dev :5173)
        │
   Gateway :8080 ── 路由 / JWT 全局校验 / 统一跨域 / traceId 生成 /(阶段五)Sentinel 限流
        │
 ┌──────────┬───────────────┬────────────────┐
 user       video        moderation         media
 :8101      :8102        :8103              :8104
 注册/登录   上传(流式写MinIO)  机审(调Python)      转码 worker(调Python/ffmpeg)
 JWT 签发   元数据/列表/详情    人工审核后台 API     MinIO 拉原片/产物回传
 用户信息    播放页API/播放量    审核通过触发转码      虚拟线程并发
```

中间件(docker compose 编排,项目 `deploy/` 目录):

| 中间件 | 端口 | 用途 |
|---|---|---|
| Nacos 3.0.3(standalone,内嵌存储) | 8848 / 9848 | 服务注册 + 配置中心 |
| RocketMQ NameServer + Broker | 9876 / 10911-10912,10909 | 异步事件 |
| Redis 7.4 | 6379 | 播放量缓冲(后续缓存扩展) |
| MinIO | 9000(API)/ 9001(控制台) | 对象存储(本机进程,Rosetta 转译官方 amd64 二进制,官方已停止 arm64 发布与 Docker Hub 分发) |

PostgreSQL 用本机原生实例(不用容器),**schema-per-service** 模拟 database-per-service:`user_db` / `video_db` / `moderation_db` / `media_db`。

Broker 部署注意:容器内 broker 默认注册容器 IP,宿主机上的 Java 服务连不上,`broker.conf` 必须设 `brokerIP1=127.0.0.1` 并映射端口。

## 5. 服务清单与职责

| 模块(artifactId) | 端口 | schema | 职责 | 依赖 |
|---|---|---|---|---|
| toys-gateway | 8080 | 无 | 统一入口:路由、JWT 全局过滤器(校验签名与过期,透传 X-User-Id/X-User-Role 头)、CORS、traceId 生成 | Nacos |
| toys-user-service | 8101 | user_db | 注册/登录(BCrypt + JWT 签发)、用户信息查询、内置种子 ADMIN | Nacos、PG |
| toys-video-service | 8102 | video_db | 上传(流式落 MinIO `videos` 私有 bucket)、视频状态机(唯一写入方)、列表/详情/我的投稿、播放量(Redis 缓冲+定时回写)、签发原片 presigned URL | Nacos、PG、MinIO、RocketMQ、Redis |
| toys-moderation-service | 8103 | moderation_db | 消费 `VIDEO_UPLOADED` 事件→机审(调 Python)→硬失败自动拒绝,否则 Feign 回写 UNDER_REVIEW;人工审核 API(队列/详情/通过/拒绝);通过后 Feign 通知 video-service | Nacos、PG、RocketMQ、scripts/ |
| toys-media-service | 8104 | media_db | 消费 `VIDEO_APPROVED` 事件→拉原片→转码(Python 脚本封装 ffmpeg,HLS 多码率+封面)→产物回传 MinIO `hls` 公开读 bucket→Feign 回写 PUBLISHED | Nacos、PG、RocketMQ、MinIO、scripts/ |

预留(不在 MVP 实现):`toys-search-service`(:8105,搜推入口,由 video-service 的 `SearchGateway`/`RecommendGateway` 接口预留)、CDN 发布(`CdnPublisher` 接口,默认空实现)。

## 6. 代码仓库结构(Maven 多模块 monorepo)

```
toys/
├── pom.xml                    # 父 POM:Java 21、依赖管理、模块列表
├── toys-common/               # 统一响应体、错误码、全局异常、工具(traceId 工具)
├── toys-api/                  # 跨服务契约:Feign 接口、事件 topic 常量、事件 payload DTO
├── toys-gateway/
├── toys-user-service/
├── toys-video-service/
├── toys-moderation-service/
├── toys-media-service/
├── frontend/                  # Vue 3 + Vite
├── scripts/                   # Python:probe_media.py / auto_screen.py / transcode.py
├── deploy/                    # docker-compose.yml、broker.conf、init SQL、README
└── docs/
```

服务间**不共享实体类**;`toys-api` 只含契约(接口 + DTO + topic 常量)。内部接口统一 `/internal/**` 前缀,由网关拒绝外部访问(网关路由只放行 `/api/**` 与 `/media/**`)。

## 7. 核心链路(事件驱动)

```
上传   分片直传(大文件/断点续传/秒传):
      前端 Web Worker 计算文件 md5 → POST /api/videos/upload/init
        → 秒传:md5 命中已完成记录 → 直接建视频记录并发机审事件,零上传
        → 续传:同用户同 md5 有未完成会话 → 返回已完成分片号,前端只补缺
        → 新会话:服务端按 5MiB~10000 片规则计算 partSize
      前端按分片 GET presign → 浏览器 PUT 直传 MinIO(chunks/{uploadId}/{n} 独立对象,
        网关不经手文件字节)→ 全部到齐 POST complete
        → 服务端校验分片数量 → composeObject 合并为 videos/{id}/original.{ext}
        → 清理分片对象 → 置 AUTO_SCREENING → 发事件 VIDEO_UPLOADED
      小文件仍保留一步 multipart 上传(POST /api/videos)。
      7 天未完成的孤儿会话在下次 init 时自动清理(abort+删分片)。

机审   moderation-service 消费(幂等:检查 status 仍为 AUTO_SCREENING)
      → 拉原片到临时目录 → Python auto_screen.py
      (ffprobe 元数据校验 + 均匀抽 8 帧 + 规则:黑帧占比/静帧/分辨率下限/时长 1s~2h)
      → AUTO_FAIL(文件损坏、无视频流):Feign 调 video-service 置 REJECTED,流程终止
      → AUTO_PASS/AUTO_SUSPECT:写 moderation_reports(auto_report JSONB),
        Feign 回写 UNDER_REVIEW(AUTO_SUSPECT 在报告中标红提示)

人工   管理员登录审核后台:队列 → 认领(POST claim,软锁 10 分钟自动释放)
      → 详情(原片 presigned URL 15 分钟 + 机审报告)
      → 通过:moderation-service Feign 调 video-service 内部接口置 APPROVED
        → video-service 发事件 VIDEO_APPROVED
      → 拒绝:写 decision=REJECTED + 原因,Feign 回写 REJECTED
      → 并发语义:决策是硬锁(条件更新),两个审核员处理同一单只有先到者生效,
        后到者收到「该任务已被其他审核员处理/认领」

转码   media-service 消费(幂等:检查 status 仍为 APPROVED)
      → Feign 置 TRANSCODING → 建 transcode_jobs(RUNNING)→ 拉原片
      → Python transcode.py(按源高度选档:≥1080→1080/720/480p;≥720→720/480p;
        更低→源高。H.264 CRF 23 + AAC 128k,4s 分片 HLS + poster.jpg)
      → 产物上传 MinIO hls/{videoId}/master.m3u8 → Feign 回写 PUBLISHED

播放   播放页 → GET /api/videos/{id}(返回 /media/hls/{id}/master.m3u8)
      → 网关把 /media/** 代理到 MinIO hls bucket → hls.js 播放
      → POST /api/videos/{id}/play 异步累加播放量
```

设计原则:**状态机变更唯一入口是 video-service**;事件只做触发,避免多服务双写同一状态。

## 8. 数据模型

```
user_db.users
  id BIGINT PK(雪花), username UNIQUE, password_hash, role(USER|ADMIN),
  created_at, updated_at
  种子:admin / admin123(BCrypt,文档注明本地学习用)

video_db.videos
  id BIGINT PK, owner_id, title, description,
  status(UPLOADED|AUTO_SCREENING|UNDER_REVIEW|APPROVED|TRANSCODING|PUBLISHED|REJECTED|TRANSCODE_FAILED),
  original_object_key, original_filename, size_bytes,
  duration_sec, width, height, format,
  play_count BIGINT DEFAULT 0, published_at, created_at, updated_at

moderation_db.moderation_reports
  id PK, video_id, auto_verdict(AUTO_PASS|AUTO_FAIL|AUTO_SUSPECT),
  auto_report JSONB(checks 明细、抽帧统计), 
  decision(PENDING|APPROVED|REJECTED), reviewer_id, reject_reason, decided_at, created_at

media_db.transcode_jobs
  id PK, video_id, status(PENDING|RUNNING|SUCCESS|FAILED),
  attempts INT DEFAULT 0, max_attempts 3, error TEXT,
  payload JSONB(档位信息), started_at, finished_at, created_at
```

各服务用 Flyway 独立管理自己的 schema;雪花 ID 用统一工具(toys-common)。

## 9. 对外 API(经网关,`/api` 前缀)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/register | 注册(用户名+密码) |
| POST | /api/auth/login | 登录,返回 JWT(含 userId、role) |
| GET | /api/users/me | 当前用户 |
| POST | /api/videos | 上传(multipart:file + title + description),需登录 |
| GET | /api/videos | 已发布视频分页列表(首页) |
| GET | /api/videos/{id} | 详情(含播放地址;仅 PUBLISHED 对普通用户可见) |
| POST | /api/videos/{id}/play | 播放量 +1(Redis 缓冲) |
| GET | /api/my/videos | 我的投稿(全状态,含失败原因) |
| GET | /api/admin/moderation/queue | 待审核队列(ADMIN) |
| GET | /api/admin/moderation/{videoId} | 审核详情(机审报告 + 原片 presigned URL) |
| POST | /api/admin/moderation/{videoId}/approve | 通过(ADMIN) |
| POST | /api/admin/moderation/{videoId}/reject | 拒绝(ADMIN,需 reason) |
| GET | /media/** | 网关代理 MinIO hls bucket(master.m3u8、分片、poster.jpg) |

统一响应体 `{code, message, data}`;错误码分段(1xxx 网关/认证、2xxx 视频、3xxx 审核、4xxx 转码)。

## 10. 配置与可观测

- Nacos 配置中心:namespace `toys-video`,group `DEFAULT_GROUP`;`common.yml`(PG/MinIO/Redis 公共配置)+ 各服务 `{service}.yml`;本地 `application.yml` 只留服务名、端口、Nacos 地址。
- traceId:网关生成(X-Request-Id)→ MDC → 日志格式含 traceId → Feign 拦截器透传 → MQ 消息 header 透传 → 消费侧恢复 MDC。
- 敏感配置(PG 密码、MinIO 密钥、JWT secret)放 Nacos 配置,`deploy/README` 提供导入脚本;本机学习环境明文可接受。

## 11. 可靠性与错误处理

- MQ 消费:失败进入重试(应用层 catch + 重发延迟消息,3 次后落库标记 FAILED,人工介入);消费幂等:处理前检查 videos.status 是否仍处于可推进状态。
- 转码作业:attempts < 3 自动重试,超限 TRANSCODE_FAILED 终态,「我的投稿」与审核后台可见错误;临时文件处理完即删,启动时清理孤儿临时目录。
- 上传:类型白名单 + 大小限制;MinIO 写入失败快速中断并删除半截对象。
- 播放:首页只出 PUBLISHED;HLS 404/错误时前端显示错误态。
- 网关:内部接口 `/internal/**` 一律 403;未认证访问受保护路由返回 401。

## 12. 前端(6 页)

1. 首页 `/`:已发布视频卡片流(封面、标题、UP主、时长、播放量)
2. 播放页 `/watch/:id`:hls.js 播放、标题/简介/UP主/播放量
3. 上传页 `/upload`:表单 + 进度条,提交后跳「我的投稿」
4. 登录/注册 `/login`、`/register`
5. 我的投稿 `/my`:全状态列表 + 状态机当前位置 + 失败原因
6. 审核后台 `/admin/review`:队列、内嵌原片播放、机审报告展示、通过/拒绝

Vite 代理:开发期 `/api`、`/media` 代理到 `http://localhost:8080`。路由守卫:未登录跳 `/login`;ADMIN 才能进后台。

## 13. Python 脚本契约(scripts/,纯标准库)

| 脚本 | 输入 | 输出(stdout JSON) |
|---|---|---|
| probe_media.py | `<input>` | `{duration_sec, width, height, has_video, has_audio, format}` |
| auto_screen.py | `<input> --frames 8 --workdir <tmp>` | `{verdict: AUTO_PASS\|AUTO_SUSPECT\|AUTO_FAIL, checks:[{name, passed, detail}]}` |
| transcode.py | `<input> <out_dir>` | `{renditions:[{name, height, bitrate}], poster}` 并在 out_dir 生成 master.m3u8 + 分片 + poster.jpg |

Java 侧用 ProcessBuilder 调用,捕获 stderr 用于错误诊断;脚本非零退出码视为失败。

## 14. 测试与验收

- 单元测试:状态机流转、雪花 ID、JWT 工具、网关鉴权过滤器逻辑。
- 集成测试:各服务连本机 PG 的测试 schema(实现前先创建 `toys_test` 系列库);MQ/MinIO 用真实本地实例(本机已装,不起 Testcontainers)。
- 脚本自测:ffmpeg 合成样例视频,验证三个 Python 脚本输出。
- 端到端验收:第 1 节成功标准 3、4 的手工清单(写入 deploy/README)。

## 15. 分阶段实施

1. **阶段一**:git 仓库骨架(父 POM + 7 个 Maven 模块)、`deploy/docker-compose.yml` 起 Nacos/RocketMQ/Redis/MinIO、toys-common(响应体/错误码/traceId)、toys-gateway(路由+JWT 过滤器)、toys-user-service(注册/登录,配置走 Nacos)。验收:经网关注册登录成功。
2. **阶段二**:toys-video-service 上传落 MinIO + 列表/详情/我的投稿。验收:上传后 MinIO 控制台可见原片,前端可列表。
3. **阶段三**:toys-moderation-service(MQ 消费机审 + 人工审核 API)+ 前端审核后台。验收:上传后状态自动推进到 UNDER_REVIEW,管理员可拒绝/通过。
4. **阶段四**:toys-media-service 转码 + 播放打通 + 播放量异步计数(Redis 缓冲+定时回写)+ 前端播放页/首页/上传页完整化。验收:端到端全链路。
5. **阶段五(可选增强)**:Sentinel 限流熔断(网关路由级)、预签名直传、search-service 骨架。

## 15b-4. 分区体系与页面扩充(第十轮补充)

- 分区/标签:videos 增 category/tags 字段,VideoMetaPolicy 纯函数校验(分区白名单 8 类、标签 ≤5 个);投稿/编辑携带;列表按分区筛选;categories API 返回分区+计数。
- 新页面:分区浏览页 /channel/:key、UP 主公开主页 /uploader/:id(profile+投稿分页+总播放统计)、404 页;导航分区下拉;投稿页两列改版(本地视频预览+分区+标签);播放页简介折叠与分区/标签 chips。

## 15b-3. 搜索与推荐(第八轮补充,多路召回+融合骨架)

- 搜索:pg_trgm 扩展 + GIN 索引,标题 ILIKE/相似度加权排序(播放量、点赞),异常自动降级 LIKE。
- 推荐:三路召回——热度榜(log10(播放+1)×100+点赞×30 除以时间衰减幂)、播放历史共现(看过 X 的人还看过什么,itemCF 简化)、UP 主偏好(历史最常看 TOP3 UP 的热门视频);各路 min-max 归一化加权融合(登录 0.4/0.4/0.2,匿名 0.7/0.3/0),去重、融合序分页、最新发布补齐。
- 相关视频:共现为主补热度,Redis 缓存 300s;播放页「相关推荐」栏。
- 演进路径:骨架(多路召回+融合)不变,任一路可替换为 ES(搜索)或 Spark 离线相似度/向量召回(推荐)。

## 15b-2. 敏感词库行业化(第五轮补充)

- 词库托管:moderation_db.sensitive_words(REJECT/REVIEW 分级、启停状态、分类),ADMIN 管理 API(CRUD/批量导入/搜索分页)与审核中心「敏感词库」管理界面。
- 热更新:写操作即重载;30s 版本比对自愈(moderation 查 MAX(updated_at));video-service 经 Feign 快照按版本同步、volatile A-B 原子替换 DFA——全程不重启。
- 变体对抗:NFKC 归一化 + 全角转半角 + 零宽字符与穿透分隔符剔除。
- 分级语义:REJECT 拒绝(评论/弹幕/标题简介);REVIEW 不拦截,进机审报告 reviewHits 供人审。
- 生产扩展位:SensitiveWordFilter 为纯 DFA 实现,可再叠加云厂商内容安全客户端做兜底。

## 16. 部署拓扑与跨域策略

- 互动:点赞(video_likes 唯一约束幂等、like_count 防负递减)、收藏(video_favorites,我的收藏列表)、播放历史 + 断点续播(play_histories 每用户每视频一行,upsert 上报,详情返回 resumePosition);视频删除级联清理三表。
- 账号:修改密码(旧密码校验,改密后当前 jti 立即失效强制重登);JWT 注销采用 jti 白名单(登录登记 Redis,登出/改密删除,网关受保护路径校验,Redis 故障降级为纯验签)。
- 审核增强:标题/简介文本机审(DFA 敏感词,命中即 AUTO_FAIL 自动拒绝并跳过画面机审);审核认领展示。
- 播放量防刷:同 IP+用户(或登录用户)24h 去重(Redis SETNX)。
- 网关限流:Redis 令牌桶(auth 10/s、admin 20/s、video 50/s),key 为登录用户或 XFF 首段客户端 IP;429 前端有明确提示。

## 16. 部署拓扑与跨域策略

本地开发:Vite dev server 代理 `/api`、`/media` 到网关 8080,浏览器始终同源,不存在跨域。

部署到服务器(参考拓扑,单机 Nginx 入口):

```
浏览器 ── 同一域名 ──> Nginx :80
                      ├── /            → 前端静态文件(Vue build 产物)
                      ├── /api/**      → Gateway :8080
                      └── /media/**    → Gateway :8080 → MinIO(hls bucket)
```

- **单源原则**:前端、API、HLS 分片全部同域名,由 Nginx 统一入口,浏览器视角无跨域。
- MinIO 的 9000/9001 端口**不对公网开放**,浏览器从不直连 MinIO;HLS 通过网关代理访问。
- 网关保留全局 CORS 配置(origin 白名单走 Nacos 配置,默认关闭),以备前端与 API 分域部署(如静态资源上 CDN)的场景。
- **直传已启用**:分片上传走预签名 PUT 直传 MinIO(网关不经手文件字节),`videos` bucket 需允许跨域 PUT(MinIO 默认 CORS 全放行);HLS 读取仍走网关 `/media/**` 代理,浏览器与 MinIO 的原片/分片交互均带预签名,不暴露写权限。
- HLS 场景注意:Nginx 代理 `/media/**` 需透传 Range 头(m3u8 分片可拖动进度条),`proxy_buffering off`。

## 17. 交互性与健壮性要求

前端交互:

- 上传页:三阶段进度(指纹计算 → 分片直传 → 合并提交);文件指纹(md5)驱动秒传与断点续传,暂停/失败后重新选择同一文件即从断点续传;分片 3 并发、单片失败重试 3 次(指数退避);前端预校验类型与 2GB 上限;提交后跳「我的投稿」并看到状态实时推进(轮询 5s,直到终态停止)。
- 播放页:hls.js 加载失败显示错误态 + 重试按钮;清晰的空状态(视频不存在/未发布/审核中不可看)。
- 我的投稿:每条视频显示状态机当前节点与失败原因(拒绝原因/转码错误);自动刷新。
- 审核后台:队列自动刷新;机审报告结构化展示(checks 列表,不裸 JSON);拒绝必填原因;防重复操作(按钮 loading 态)。
- 全局:登录态过期统一拦截跳登录;表单校验与 loading 骨架屏;错误信息可读(映射统一错误码,不裸抛异常)。

后端健壮性:

- 消费幂等:机审/转码消费前校验 videos.status 处于可推进状态,重复投递不产生副作用;同状态重入按幂等无操作处理。
- 上传幂等:complete 前条件更新原子确认会话持有权,并发双合并被拒;分片数量不足拒绝合并。
- 状态机模式:视频状态流转收敛于 VideoStateMachine(转移表+校验),UploadPolicy/TranscodePolicy 承载策略参数。
- 降级兜底:Feign 全部挂 fallbackFactory(下游不可用返回明确语义);MinIO 关键操作经重试执行器(3 次退避);media 启动时回收 RUNNING 孤儿作业。
- 认证防爆破:同用户名连续失败 5 次锁定 15 分钟;注册按 IP 限频。
- 优雅停机:各服务 server.shutdown=graceful + 生命周期超时;worker 停机不再领取新任务、在跑任务收尾。
- 对象存储:各服务启动时幂等确保 bucket 存在及策略正确;上传中断清理半截对象;转码临时目录用完即删、启动清理孤儿目录;7 天未完成的分片会话自动清理。
- 删除级联:视频删除时跨服务清理孤儿数据——moderation 审核报告、media 转码作业(内部接口,失败仅告警),本服务内评论/弹幕/点赞/收藏/历史与 MinIO 对象同步清理(秒传共享对象除外);审核详情对已删视频返回明确 404 语义。
- 健康检查:各服务引入 Spring Boot Actuator `/actuator/health`,docker-compose 与运维探活依赖它。
- 网关:路由超时与回退兜底(下游不可用返回统一 503 响应体,而非连接错误裸页);Authorization 头含控制字符直接 401。
- 播放量:Redis 缓冲 + 定时批量回写,服务重启不丢(Redis AOF 兜底,学习项目可接受)。
- 边界:标题/简介长度限制,文件名防路径穿越清洗,md5 格式校验,用户名唯一冲突返回明确错误码,JWT 过期/伪造统一 401 语义,超长 token 拒绝。

## 18. 扩展路线(仅记录方向,不实现)

- 上传改预签名直传 MinIO(绕过网关带宽),视频服务预签名 + MinIO 事件回调
- search-service:接入 ES/Easysearch,视频发布事件驱动索引
- 搜推算法:RecommendGateway 接口替换(热度/协同过滤)
- CDN:CdnPublisher 接口对接(域名 + URL 签名),HLS 产物发布到边缘
- RocketMQ 事务消息/本地事件表,替代当前"先落库后发事件"的简单顺序
- SkyWalking 全链路追踪;Sentinel 规则持久化到 Nacos
- 业务扩展:弹幕、评论、点赞、收藏(user_db/video_db 扩表)

## 19. 已确认的关键决策记录

1. 目标定位:学习微服务整套流程,架构可扩展性优先(非最快跑通)。
2. 技术栈:Spring Cloud Alibaba 全套(Nacos/OpenFeign/Gateway/Sentinel/RocketMQ),MinIO 对象存储,Redis。
3. 审核形态:自动初筛(Python ffprobe+抽帧规则)+ 人工审核兜底。
4. 账号体系:极简 JWT,角色 USER/ADMIN。
5. 前端:Vue 3 + Vite + Element Plus + hls.js。
6. 数据库:本机 PostgreSQL,schema-per-service。
7. 转码在人工审核通过后执行,拒绝的视频不转码。
