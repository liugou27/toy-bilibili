# toys-video 后端架构与技术细节

> 配套交互式架构图:[backend-architecture.html](architecture/backend-architecture.html)(浏览器打开,支持缩放/主题/关系追踪)
> 设计规格与演进记录:[2026-09-25-video-platform-microservices-design.md](superpowers/specs/2026-09-25-video-platform-microservices-design.md)

## 1. 系统概览

Spring Cloud Alibaba 微服务视频平台,覆盖 **上传 → 机审 → 人工审核 → 转码 → 播放 → 互动** 全链路。

| 层 | 选型 |
|---|---|
| 语言/运行时 | Java 21(media-service 虚拟线程)、Python 3 标准库(媒体脚本)、ffmpeg 8.x |
| 框架 | Spring Boot 3.5.x、Spring Cloud 2025.0.x、Spring Cloud Alibaba 2025.0.0.0 |
| RPC | OpenFeign(同步,带 FallbackFactory 兜底)+ RocketMQ 5.3(异步事件) |
| 注册/配置 | Nacos 3.0.3(全部服务注册;配置以本地 application.yml 为默认,Nacos 可覆盖) |
| 存储 | PostgreSQL 18(schema-per-service)、MinIO 对象存储(videos 私有 / hls 公读)、Redis 7.4 |
| 网关 | Spring Cloud Gateway(WebFlux):JWT 鉴权、Redis 令牌桶限流、/media 代理、统一错误出口 |
| ORM | MyBatis-Plus + Flyway 迁移(V1~V6) |

**模块划分**(Maven monorepo):`toys-common`(响应/异常/traceId/JWT/雪花/布隆/DFA 词滤/脚本执行器)→ `toys-api`(Feign 契约+事件 DTO+topic 常量,服务间不共享实体)→ 5 个服务 + `frontend/` + `scripts/` + `deploy/`。

## 2. 服务清单

| 服务 | 端口 | schema | 职责要点 |
|---|---|---|---|
| toys-gateway | 8080 | — | 唯一入口:路由、JWT+jti 校验、三级限流、CORS、traceId、503 兜底 |
| toys-user-service | 8101 | user_db | 注册(布隆预检+防刷)、登录(防爆破+BCrypt)、注销、改密、资料(昵称/头像) |
| toys-video-service | 8102 | video_db | 上传体系、状态机(唯一写入方)、搜推、互动、分区标签、播放量 |
| toys-moderation-service | 8103 | moderation_db | 文本+画面机审、敏感词库中心(热更新)、人工审核(认领/决策) |
| toys-media-service | 8104(可多实例) | media_db | 转码集群 worker(HLS 多码率+封面)、租约抢占、心跳续期、故障抢回、失败重试 |

## 3. 核心链路(端到端)

```
① 投稿:前端 md5 Worker 指纹 → POST /videos/upload/init
   ├─ 秒传:md5 命中已完成记录 → 直接建记录(带表单元数据)→ 发事件 → 机审
   ├─ 续传:同用户同 md5 未完成会话 → 返回已完成分片号,前端只补缺
   └─ 新会话:5MiB~10000 片策略分片 → 逐片 GET presign → 浏览器 PUT 直传 MinIO
             (chunks/{uploadId}/{n},网关不经手字节)→ complete 校验分片数
             → composeObject 服务端合并 → 清理分片 → 置 AUTO_SCREENING → 发 VIDEO_UPLOADED
② 机审:moderation 消费(幂等:状态仍为 AUTO_SCREENING 才处理)
   ├─ 文本先行:DFA 敏感词(REJECT 命中 → 自动拒绝,跳过画面机审;REVIEW → 记报告待人工)
   └─ 画面:Python auto_screen.py(ffprobe 元数据 + 均匀抽 8 帧:黑帧/静帧/分辨率/时长)
③ 人工:审核后台队列(10s 轮询)→ 认领(软锁 10 分钟,超时自动释放)
   → 详情(原片 presigned 15min + 机审报告结构化)→ 通过/拒绝(硬锁,条件更新)
   → 通过则 Feign 置 APPROVED → video-service 发 VIDEO_APPROVED
④ 转码:media 集群消费(同组多实例)→ transcode_jobs 租约抢占(条件更新,PENDING/FAILED 或租约过期的 RUNNING)
   → Python transcode.py(按源高 1080/720/480 档,H.264 CRF23 + AAC,4s 分片 HLS + poster)
   → 产物回传 MinIO hls/{id}/ → Feign 置 PUBLISHED;失败重试 3 次(MQ 重投递)→ TRANSCODE_FAILED
⑤ 播放:GET /videos/{id} 返回 /media/hls/{id}/master.m3u8 → 网关代理 MinIO(支持 Range)
   → Artplayer + hls.js;播放量 Redis SETNX 24h 去重 + 10s 批量回写 DB
```

状态机(`VideoStateMachine`,唯一写入方 video-service):
`UPLOADED → AUTO_SCREENING → UNDER_REVIEW → APPROVED → TRANSCODING → PUBLISHED`,分支 `REJECTED` / `TRANSCODE_FAILED`(投稿人可重试);同状态重入幂等。

## 4. 各服务技术细节

### 4.1 Gateway(8080)

- **鉴权**:`AuthGlobalFilter`(reactive,IO 线程禁 block)——`/internal/**` 403;白名单(`/api/auth/**`、GET `/api/videos/**`、`/media/**`、POST `/api/videos/*/play`)放行但携带有效 token 时附加身份;受保护路径校验 JWT 签名 + **jti 白名单**(登录时 user-service 写 `auth:token:{jti}`,登出/改密删除 → token 立即失效;Redis 故障 `onErrorReturn(true)` 降级为纯验签,不炸全站);`/api/admin/**` 要求 ADMIN;Authorization 头含控制字符/空格直接 401。
- **限流**:Redis 令牌桶 `RequestRateLimiter`,按路由配置——video 50/s、admin 20/s、auth 10/s;KeyResolver:登录用户(X-User-Id)优先,匿名取 XFF 首段回退 remoteAddress(实测 100 并发压出 429)。
- **/media 代理**:RewritePath 到 MinIO 9000,HLS 分片透传 Range(进度条拖动);部署拓扑见 §7。

### 4.2 user-service(8101)

- **注册防刷**:同 IP 1 分钟 >5 次拒绝(内存窗口)。
- **布隆过滤器**(`common/util/BloomFilter`,自研零依赖):FNV-1a 64 双种子 + long[] 位数组,m=−n·lnp/(ln2)²、k=m/n·ln2(n=10万、p=1% → ~117KB、7 哈希);`UsernameBloomService` 启动全量分批扫描构建,注册时 `mightContain=false` 跳过 DB 查重(true 仍查 DB,唯一约束兜底);饱和度 >50% → 容量×2 重建→全量迁移→volatile 原子替换。
- **登录防爆破**:`LoginGuard` 同用户名 5 分钟连续失败 ≥5 次 → 锁 15 分钟(第 6 次实测被拒)。
- **注销/改密**:删除当前 jti → 全站立即失效;改密后强制重登。

### 4.3 video-service(8102)——最大的服务

- **上传**:`UploadPolicy` 纯函数(分片大小策略、2GB 上限、文件名清洗防穿越、md5 格式校验);秒传分支携带表单元数据(标题/分区/标签);complete 条件更新确认会话持有权防并发双合并;7 天孤儿会话自动清理;秒传共享对象删除保护(objectKey 被其他记录引用则跳过)。
- **搜推**(`discovery/` 扩展位):搜索 = pg_trgm GIN 索引 + similarity 加权排序(异常降级 LIKE);推荐 = 三路召回 + min-max 归一化融合——热度路 `(log10(播放+1)×100+点赞×30)/(小时+2)^1.5`、共现路(播放历史 itemCF 简化)、UP 偏好路(TOP3 常看 UP),登录权重 0.4/0.4/0.2、匿名 0.7/0.3/0;`GET /videos/{id}/related` 共现为主补热度。首页列表 Redis cache-aside 60s,状态变更/新投稿整组失效。
- **互动**:点赞/收藏(唯一约束幂等、计数条件递减防负)、评论(≤500 字、DFA 拦截、仅作者可删、username/nickname 经 Feign 批量填充)、弹幕(≤100 字、timeSec 定位、全量 ≤2000 下发)、播放历史+断点续播((user,video) 唯一 upsert)。
- **分区标签**:`VideoMetaPolicy`(8 分区白名单、标签 ≤5 个×16 字);`GET /videos/categories` 计数;分区筛选走专用查询(不走融合)。
- **删除级联**:本服务评论/弹幕/点赞/收藏/历史 + MinIO 对象(含 hls 前缀)→ Feign 调 moderation/media 内部清理接口(失败仅告警)。
- **播放量**:Redis `SETNX play:dup:{vid}:{md5(clientKey)}` 24h 去重(IP+用户维度),缓冲计数 10s 批量回写。

### 4.4 moderation-service(8103)

- **词库中心**(行业形态):`sensitive_words` 表(REJECT/REVIEW 分级、启停、分类);ADMIN CRUD/批量导入/分页搜索;写操作即时重载 + 30s 版本比对自愈;`/internal/sensitive-words/snapshot?version=` 版本协商(相同返回 words=null 零开销)。
- **DFA 过滤器**(`common/text/SensitiveWordFilter`):字典树 + 归一化预处理(NFKC → 全角转半角 → 小写 → 剔除零宽字符与穿透分隔符`.`·-_*空格);词库更新 = 新建树 + volatile 原子替换(不重启);video-service 经 `SensitiveWordHolder` 30s Feign 快照同步同样原子替换。
- **机审**:文本先行(REJECT 拒绝带命中词 reason;REVIEW 记 reviewHits)→ 画面 Python 脚本;报告 jsonb 落库。
- **人工审核**:认领(claimed_by 软锁,10 分钟超时释放,@Scheduled 每分钟清扫)→ 决策(条件更新硬锁,后到者收「已被其他审核员处理」);决策前 Feign 校验视频仍在 UNDER_REVIEW 防不一致。

### 4.5 media-service(8104)

- **集群模型**:同 consumer group 多实例横向扩容,吞吐随实例数线性增长(每实例 consumeThread=1 串行转码,CPU 密集)。
- **租约抢占**:`UPDATE ... WHERE status IN ('PENDING','FAILED') OR (status='RUNNING' AND lease_until < now())` 原子领取,写入 owner_instance + lease_until + object_key;执行期间心跳线程续期(默认 30s 心跳/90s 租约)。
- **故障抢回**:`LeaseReaper` 每 30s 扫描租约过期的 RUNNING 作业就地重新派发,与 MQ 重投递互为兜底;抢回时视频已在 TRANSCODING 的状态冲突视为合法续跑。
- **成功围栏**:作业 SUCCESS 落库限定本实例持有(owner+RUNNING 条件),被抢回的迟到实例写入不生效、结果直接丢弃;作业先 SUCCESS 再视频 PUBLISHED,两步之间崩溃由 skip 分支补偿重推。
- **重试策略**:`TranscodePolicy`,attempts≥3 终态 TRANSCODE_FAILED(投稿页可重试);`StartupRecovery` 启动时清扫本机残留临时目录;MinIO 操作走 `MinioRetryExecutor`(3 次退避);临时目录用完即删。

## 5. 数据模型(4 schema,16 表)

- **user_db**:users(id/username/password_hash/role/nickname/avatar)
- **video_db**:videos(状态机全字段+md5/upload_id/part_size/category/tags/like_count)、video_likes、video_favorites、play_histories、comments、danmaku
- **moderation_db**:moderation_reports(auto_verdict/auto_report jsonb/decision/claimed_by/claimed_at)、sensitive_words
- **media_db**:transcode_jobs(status/attempts/max_attempts/error/payload jsonb/owner_instance/lease_until/object_key)

ID 全局雪花(`common/Snowflake`,时钟回拨等待);JSON 输出:ID 为字符串(防 JS 精度丢失)、计数为原生 long 数字。

## 6. 横切关注点

- **幂等**:状态机条件更新(乐观锁)、同状态重入无操作、MQ 消费前状态校验、报告/点赞/收藏唯一约束、complete 会话持有权确认。
- **兜底**:Feign 全挂 FallbackFactory(明确降级语义)、网关 Redis 故障降级验签、下游不可用统一 503 JSON、词滤 Feign 失败保留本地词库、启动词库加载失败退化为 DB 查重。
- **可观测**:traceId 网关生成 → MDC → Feign/MQ 透传,全链路日志可串联;Actuator 健康检查(容器 HEALTHCHECK 依赖)。
- **优雅停机**:全部服务 `server.shutdown: graceful` + 30s 生命周期超时。
- **已知安全边界(学习项目取舍)**:`/internal/**` 仅靠 `X-Internal-Call` 头标识服务间调用,且服务端口绑定全部网卡——同网段主机可伪造头直连内部接口(状态机/词库快照/清理)。生产形态应为服务间 mTLS 或网络隔离(compose 内网 + 仅网关暴露);本地/实验环境可接受。
- **性能实测**(bench.py):首页缓存命中 **2432 QPS / p99 153ms / 0 错误**;限流闸门按配置生效(56 QPS + 15.7 万 429)。

## 7. 部署

- **双模式 compose**(`deploy/docker-compose.yml`):默认仅中间件(Nacos/RocketMQ/Redis,本地开发);`--profile full` 增起 postgres 容器 + 5 服务镜像(`deploy/docker/Dockerfile.service`,temurin 21 + HEALTHCHECK,moderation/media 附 python3/ffmpeg)+ 前端 Nginx 镜像(`Dockerfile.frontend`,8081 端口)。全部配置环境变量参数化(默认值=本地开发值)。
- **服务器单源拓扑**(`deploy/nginx.conf.example`):Nginx 80 统一入口(静态 + /api + /media 反代网关),浏览器零跨域;MinIO 不暴露公网。
- 已知适配:MinIO 官方停发 arm64 镜像/brew → GitHub amd64 二进制 + Rosetta(`start-minio.sh`);双 broker.conf(本地 127.0.0.1 / 容器 rocketmq-broker)。
- **配置管理(Nacos 配置中心)**:`deploy/nacos-init.sh` 把 `deploy/nacos-configs/`(共享 `common.yml` + 每服务 DataId)推入 Nacos;服务以 `spring.config.import: optional:nacos:` 引用,优先级 **Nacos > 环境变量 > 本地 application.yml 默认值**——Nacos 缺席时本地默认值即可独立跑通(本地开发零配置)。各服务 application.yml 以【部署】标注环境敏感项;转码集群租约参数在 Nacos `toys-media-service.yml` 调整(租约需 > 2× 心跳间隔)。

## 8. 质量保障

- **单元测试 58+**:状态机/上传策略/JWT/雪花/布隆/词滤(归一化穿透)/评论弹幕校验/融合推荐/播放去重/历史 upsert。
- **全链路回归** `scripts/e2e_check.py`(22 步,纯标准库):注册→分片直传→秒传→机审→认领审核→转码发布→搜索分区→播放去重→互动→注销→删除级联→限流,一键验证十轮迭代全部功能。
- **压测** `scripts/bench.py`、**OCR 代码审查**:每轮 diff 经 open-code-review(累计修复 HIGH×2、MEDIUM×2、真实 bug 多处)。

## 9. 扩展位(接口已预留,替换实现即可)

`SearchGateway`(→ ES/OpenSearch)、`RecommendGateway` 各召回通道(→ Spark 离线相似度/向量召回)、`SensitiveWordFilter`(→ 云厂商内容安全兜底)、`CdnPublisher`(→ CDN 发布);弹幕/评论已具备支撑更高并发的表结构。
