package com.toys.video.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.api.event.VideoApprovedEvent;
import com.toys.video.api.event.VideoUploadedEvent;
import com.toys.video.api.feign.UserInternalClient;
import com.toys.video.common.api.PageResult;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.config.MinioConfig;
import com.toys.video.video.discovery.Categorys;
import com.toys.video.video.discovery.recommend.RecommendGateway;
import com.toys.video.video.discovery.search.SearchGateway;
import com.toys.video.video.dto.CategorySummary;
import com.toys.video.video.dto.VideoCard;
import com.toys.video.video.dto.VideoDetail;
import com.toys.video.video.dto.UploaderProfile;
import com.toys.video.video.entity.Video;
import com.toys.video.video.entity.VideoFavorite;
import com.toys.video.video.entity.VideoHistory;
import com.toys.video.video.mapper.VideoFavoriteMapper;
import com.toys.video.video.mapper.VideoHistoryMapper;
import com.toys.video.video.mapper.VideoMapper;
import com.toys.video.video.mq.VideoEventPublisher;
import com.toys.video.video.text.SensitiveWordHolder;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 视频领域服务:整个平台里 videos 状态机的唯一写入方。
 * 机审/转码服务只能通过 internal 接口请求状态推进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    /** 分片上传文件大小上限:2GB(与网关 multipart 限制一致)。 */
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;

    private final VideoMapper videoMapper;
    private final VideoFavoriteMapper favoriteMapper;
    private final VideoHistoryMapper historyMapper;
    private final VideoEventPublisher eventPublisher;
    private final InteractionService interactionService;
    private final HistoryService historyService;
    private final CommentService commentService;
    private final DanmakuService danmakuService;
    private final io.minio.MinioClient minioClient;
    private final SearchGateway searchGateway;
    private final RecommendGateway recommendGateway;
    private final UserInternalClient userInternalClient;
    private final com.toys.video.api.feign.ModerationInternalClient moderationInternalClient;
    private final com.toys.video.api.feign.TranscodeInternalClient transcodeInternalClient;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final SensitiveWordHolder sensitiveWordHolder;

    private static final String LIST_CACHE_PREFIX = "cache:videos:list:";
    private static final java.time.Duration LIST_CACHE_TTL = java.time.Duration.ofSeconds(60);
    private static final String RELATED_CACHE_PREFIX = "cache:videos:related:";
    private static final java.time.Duration RELATED_CACHE_TTL = java.time.Duration.ofSeconds(300);

    @Value("${toys.upload.allowed-exts:mp4,mkv,mov,avi,flv}")
    private String allowedExts;

    public com.toys.video.video.dto.UploadResponse upload(MultipartFile file, String title, String description,
                                                          String category, String tags, Long ownerId) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "文件不能为空");
        }
        if (title == null || title.isBlank() || title.length() > 100) {
            throw BizException.of(ErrorCode.VIDEO_TITLE_INVALID);
        }
        if (description != null && description.length() > 2000) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "简介过长");
        }
        String normalizedCategory = VideoMetaPolicy.normalizeCategory(category);
        String normalizedTags = VideoMetaPolicy.normalizeTags(tags);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extOf(filename);
        if (!allowedExtsAllowed(ext)) {
            throw BizException.of(ErrorCode.VIDEO_TYPE_FORBIDDEN);
        }

        Video video = new Video();
        video.setOwnerId(ownerId);
        video.setTitle(title.trim());
        video.setDescription(description == null ? "" : description.trim());
        video.setCategory(normalizedCategory);
        video.setTags(normalizedTags);
        video.setStatus(VideoStatus.UPLOADED.name());
        videoMapper.insert(video);

        String objectKey = video.getId() + "/original." + ext;
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(MinioConfig.BUCKET_VIDEOS)
                    .object(objectKey)
                    .stream(in, file.getSize(), -1)
                    .contentType("application/octet-stream")
                    .build());
        } catch (Exception e) {
            log.error("upload to minio failed, videoId={}", video.getId(), e);
            videoMapper.deleteById(video.getId());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "视频存储失败,请重试");
        }

        video.setObjectKey(objectKey);
        video.setOriginalFilename(filename);
        video.setSizeBytes(file.getSize());
        video.setStatus(VideoStatus.AUTO_SCREENING.name());
        videoMapper.updateById(video);

        try {
            eventPublisher.publishUploaded(new VideoUploadedEvent(
                    video.getId(), objectKey, ownerId, filename,
                    video.getTitle(), video.getDescription()));
        } catch (Exception e) {
            log.error("publish VIDEO_UPLOADED failed, videoId={}, discarding record", video.getId(), e);
            discardFailedUpload(video.getId(), objectKey);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "任务提交失败,请重试");
        }
        return new com.toys.video.video.dto.UploadResponse(video.getId(), video.getStatus());
    }

    /**
     * 首页列表:cache-aside,60s TTL;关键词搜索/登录个性化/匿名融合分流,状态变更/新投稿时整组失效。
     * 带合法 category 时改走分区专用查询(按 category + PUBLISHED 分页),缓存 key 同样按 category 区分。
     */
    public PageResult<VideoCard> publishedPage(long page, long size, String keyword, Long userId, String category) {
        String cat = VideoMetaPolicy.normalizeCategory(category);
        String key = listCacheKey(page, size, keyword, userId, cat);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        new com.fasterxml.jackson.core.type.TypeReference<PageResult<VideoCard>>() {});
            }
        } catch (Exception e) {
            log.warn("list cache read failed, fall back to db: {}", e.getMessage());
        }
        PageResult<VideoCard> result;
        if (cat != null) {
            result = categoryPage(cat, page, size);
        } else {
            IPage<Video> p;
            if (keyword != null && !keyword.isBlank()) {
                p = searchGateway.searchPublished(keyword, page, size);
            } else if (userId != null) {
                p = recommendGateway.recommendForUser(userId, page, size);
            } else {
                p = recommendGateway.recommend(page, size);
            }
            result = toCards(p);
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(result), LIST_CACHE_TTL);
        } catch (Exception e) {
            log.warn("list cache write failed: {}", e.getMessage());
        }
        return result;
    }

    /** 分区筛选页:PUBLISHED + 指定分区,发布时间倒序;不接入融合推荐。 */
    public PageResult<VideoCard> categoryPage(String category, long page, long size) {
        IPage<Video> p = videoMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getStatus, VideoStatus.PUBLISHED.name())
                        .eq(Video::getCategory, category)
                        .orderByDesc(Video::getPublishedAt));
        return toCards(p);
    }

    private String listCacheKey(long page, long size, String keyword, Long userId, String category) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        // 融合推荐结果因人而异:匿名与登录用户、不同登录用户分别缓存
        String user = userId == null ? "anon" : "u" + userId;
        return LIST_CACHE_PREFIX + page + ":" + size + ":" + sha256Hex(kw) + ":" + user
                + ":" + (category == null ? "" : category);
    }

    /** 关键词的小写十六进制 SHA-256:避免 String.hashCode 碰撞导致不同关键词命中同一缓存。 */
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** 相关视频:共现为主、热度补齐;cache-aside,300s TTL。 */
    public PageResult<VideoCard> related(Long videoId, int size) {
        String key = RELATED_CACHE_PREFIX + videoId + ":" + size;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        new com.fasterxml.jackson.core.type.TypeReference<PageResult<VideoCard>>() {});
            }
        } catch (Exception e) {
            log.warn("related cache read failed, fall back to db: {}", e.getMessage());
        }
        PageResult<VideoCard> result = toCards(recommendGateway.related(videoId, size));
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(result), RELATED_CACHE_TTL);
        } catch (Exception e) {
            log.warn("related cache write failed: {}", e.getMessage());
        }
        return result;
    }

    /** 分区概览:全部分区(有序)及各自 PUBLISHED 视频数,一条 group by 统计。 */
    public List<CategorySummary> categories() {
        Map<String, Long> counts = videoMapper.countPublishedByCategory().stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("category")),
                        row -> ((Number) row.get("cnt")).longValue(),
                        (a, b) -> a));
        return Categorys.all().stream()
                .map(c -> new CategorySummary(c.key(), c.name(), counts.getOrDefault(c.key(), 0L)))
                .toList();
    }

    // ==================== UP 主公开主页 ====================

    /** UP 主公开主页:展示名/头像来自 user-service,仅统计其 PUBLISHED 视频;用户不存在返回 NOT_FOUND。 */
    public UploaderProfile uploaderProfile(Long uploaderId) {
        UserInternalClient.UserBrief brief = fetchBrief(uploaderId);
        String name = brief.nickname() != null && !brief.nickname().isBlank()
                ? brief.nickname() : brief.username();
        if (name == null || name.isBlank()) {
            name = "用户" + uploaderId;
        }
        Map<String, Object> stats = videoMapper.selectPublishedStats(uploaderId);
        long videoCount = stats == null || stats.get("video_count") == null
                ? 0 : ((Number) stats.get("video_count")).longValue();
        long totalPlayCount = stats == null || stats.get("total_play_count") == null
                ? 0 : ((Number) stats.get("total_play_count")).longValue();
        return new UploaderProfile(uploaderId, name, brief.avatar(), videoCount, totalPlayCount);
    }

    /** UP 主公开视频列表:仅 PUBLISHED,发布时间倒序。 */
    public PageResult<VideoCard> uploaderVideos(Long uploaderId, long page, long size) {
        IPage<Video> p = videoMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getOwnerId, uploaderId)
                        .eq(Video::getStatus, VideoStatus.PUBLISHED.name())
                        .orderByDesc(Video::getPublishedAt));
        return toCards(p);
    }

    /** 单个用户展示信息:batch 空回退或 Feign 失败(用户不存在)→ NOT_FOUND。 */
    private UserInternalClient.UserBrief fetchBrief(Long userId) {
        List<UserInternalClient.UserBrief> briefs;
        try {
            briefs = userInternalClient.batch(List.of(userId)).data();
        } catch (Exception e) {
            log.warn("fetch user brief failed, userId={}: {}", userId, e.getMessage());
            throw BizException.of(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (briefs == null || briefs.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return briefs.get(0);
    }

    /** 列表/相关视频缓存整组失效:任何会影响首页可见内容的写入后调用。 */
    private void evictListCache() {
        try {
            java.util.Set<String> keys = redis.keys(LIST_CACHE_PREFIX + "*");
            java.util.Set<String> relatedKeys = redis.keys(RELATED_CACHE_PREFIX + "*");
            if (keys == null) {
                keys = new java.util.HashSet<>();
            }
            if (relatedKeys != null) {
                keys.addAll(relatedKeys);
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("list cache evict failed: {}", e.getMessage());
        }
    }

    public VideoDetail detail(Long id, Long requesterId, String requesterRole) {
        Video video = videoMapper.selectById(id);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        boolean ownerOrAdmin = video.getOwnerId().equals(requesterId) || "ADMIN".equals(requesterRole);
        if (!VideoStatus.PUBLISHED.name().equals(video.getStatus()) && !ownerOrAdmin) {
            throw BizException.of(ErrorCode.VIDEO_NOT_PUBLISHED);
        }
        return toDetail(video, requesterId);
    }

    public PageResult<VideoCard> mine(Long ownerId, long page, long size) {
        IPage<Video> p = videoMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getOwnerId, ownerId)
                        .orderByDesc(Video::getCreatedAt));
        return toCards(p);
    }

    /** 我的收藏:按收藏时间倒序。 */
    public PageResult<VideoCard> favoritePage(Long userId, long page, long size) {
        IPage<VideoFavorite> p = favoriteMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<VideoFavorite>()
                        .eq(VideoFavorite::getUserId, userId)
                        .orderByDesc(VideoFavorite::getCreatedAt));
        Map<Long, Video> videos = videosByIds(p.getRecords().stream().map(VideoFavorite::getVideoId).toList());
        List<Video> ordered = p.getRecords().stream()
                .map(f -> videos.get(f.getVideoId()))
                .filter(Objects::nonNull)
                .toList();
        return toCards(ordered, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 我的播放历史:按最近观看倒序,卡片带断点位置。 */
    public PageResult<VideoCard> historyPage(Long userId, long page, long size) {
        IPage<VideoHistory> p = historyMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<VideoHistory>()
                        .eq(VideoHistory::getUserId, userId)
                        .orderByDesc(VideoHistory::getUpdatedAt));
        Map<Long, Video> videos = videosByIds(p.getRecords().stream().map(VideoHistory::getVideoId).toList());
        Map<Long, Double> positions = p.getRecords().stream()
                .collect(Collectors.toMap(VideoHistory::getVideoId, VideoHistory::getPositionSec, (a, b) -> a));
        List<Video> ordered = p.getRecords().stream()
                .map(h -> videos.get(h.getVideoId()))
                .filter(Objects::nonNull)
                .toList();
        PageResult<VideoCard> cards = toCards(ordered, p.getTotal(), p.getCurrent(), p.getSize());
        List<VideoCard> withPosition = cards.list().stream()
                .map(c -> new VideoCard(c.id(), c.title(), c.poster(), c.durationSec(), c.playCount(),
                        c.ownerId(), c.ownerName(), c.status(), c.note(), c.category(), c.tags(),
                        c.publishedAt(), positions.get(c.id())))
                .toList();
        return new PageResult<>(withPosition, cards.total(), cards.page(), cards.size());
    }

    /** 机审/转码服务请求推进状态机; UNDER_REVIEW→APPROVED 会同步发出转码事件。 */
    public void updateStatusInternal(Long id, com.toys.video.api.feign.VideoInternalClient.InternalStatusUpdate update) {
        VideoStatus target = parseStatus(update.target());
        Video video = videoMapper.selectById(id);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        VideoStatus current = VideoStatus.valueOf(video.getStatus());
        // 同状态重入:幂等无操作(消息重投递场景)
        if (current == target) {
            log.info("video {} already {}, idempotent skip", id, current);
            return;
        }
        VideoStateMachine.requireTransit(current, target);
        LambdaUpdateWrapper<Video> uw = new LambdaUpdateWrapper<Video>()
                .eq(Video::getId, id)
                .eq(Video::getStatus, current.name())
                .set(Video::getStatus, target.name())
                .set(Video::getNote, update.note())
                .set(Video::getUpdatedAt, LocalDateTime.now());
        if (update.durationSec() != null) {
            uw.set(Video::getDurationSec, update.durationSec());
        }
        if (update.width() != null) {
            uw.set(Video::getWidth, update.width());
        }
        if (update.height() != null) {
            uw.set(Video::getHeight, update.height());
        }
        if (target == VideoStatus.PUBLISHED) {
            uw.set(Video::getPublishedAt, LocalDateTime.now());
        }
        int rows = videoMapper.update(null, uw);
        if (rows == 0) {
            throw BizException.of(ErrorCode.VIDEO_STATUS_CONFLICT);
        }
        log.info("video {} status {} -> {}", id, current, target);
        evictListCache();
        if (target == VideoStatus.APPROVED) {
            try {
                eventPublisher.publishApproved(new VideoApprovedEvent(video.getId(), video.getObjectKey()));
            } catch (RuntimeException e) {
                // 事件发布失败回退到 UNDER_REVIEW,机审可重新推进,避免视频卡死在 APPROVED
                log.error("publish VIDEO_APPROVED failed, rollback video {} to UNDER_REVIEW", id, e);
                videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                        .eq(Video::getId, id)
                        .eq(Video::getStatus, VideoStatus.APPROVED.name())
                        .set(Video::getStatus, VideoStatus.UNDER_REVIEW.name())
                        .set(Video::getUpdatedAt, LocalDateTime.now()));
                throw e;
            }
        }
    }

    /** 转码失败后的重试:回到 APPROVED 并重新发事件。 */
    public void retryTranscode(Long id, Long requesterId, String requesterRole) {
        Video video = videoMapper.selectById(id);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        boolean ownerOrAdmin = video.getOwnerId().equals(requesterId) || "ADMIN".equals(requesterRole);
        if (!ownerOrAdmin) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (!VideoStatus.TRANSCODE_FAILED.name().equals(video.getStatus())) {
            throw BizException.of(ErrorCode.VIDEO_STATUS_CONFLICT);
        }
        // 先发事件再改状态:发布失败保持 TRANSCODE_FAILED,可直接重试
        eventPublisher.publishApproved(new VideoApprovedEvent(id, video.getObjectKey()));
        int rows = videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                .eq(Video::getId, id)
                .eq(Video::getStatus, VideoStatus.TRANSCODE_FAILED.name())
                .set(Video::getStatus, VideoStatus.APPROVED.name())
                .set(Video::getNote, null)
                .set(Video::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ErrorCode.VIDEO_STATUS_CONFLICT);
        }
    }

    /** 删除投稿:owner 或 ADMIN,任何状态;物理删除记录并清理归属对象。 */
    public void deleteVideo(Long id, Long requesterId, String requesterRole) {
        Video video = videoMapper.selectById(id);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        if (!video.getOwnerId().equals(requesterId) && !"ADMIN".equals(requesterRole)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        // 共享判定先行:DB 行删除后引用计数口径不变,提前计算保持语义清晰
        boolean objectShared = video.getObjectKey() != null && objectKeySharedByOthers(video);
        boolean hasChunkSession = VideoStatus.UPLOADED.name().equals(video.getStatus()) && video.getUploadId() != null;
        List<Integer> chunkParts = hasChunkSession ? listUploadedPartNumbers(video) : List.of();

        // 先删 DB 行与级联数据:任何一步失败直接抛错中止,不会留下指向已删对象的悬空行
        videoMapper.deleteById(id);
        interactionService.deleteByVideo(id);
        historyService.deleteByVideo(id);
        commentService.deleteByVideo(id);
        danmakuService.deleteByVideo(id);
        evictListCache();

        // 再做对象清理:失败仅告警,最坏留下孤儿对象而非不可用的记录
        if (hasChunkSession) {
            deleteChunks(video.getUploadId(), chunkParts);
        }
        // 原片被其他视频(秒传共享)引用时保留,否则一并删除
        if (video.getObjectKey() != null && !objectShared) {
            removeObject(MinioConfig.BUCKET_VIDEOS, video.getObjectKey());
        }
        removePrefix(MinioConfig.BUCKET_HLS, video.getId() + "/");
        // 跨服务孤儿数据清理:失败仅告警,不影响删除结果
        try {
            moderationInternalClient.purgeReports(id);
        } catch (Exception e) {
            log.warn("purge moderation reports failed for video {}: {}", id, e.getMessage());
        }
        try {
            transcodeInternalClient.purgeJobs(id);
        } catch (Exception e) {
            log.warn("purge transcode jobs failed for video {}: {}", id, e.getMessage());
        }
        log.info("video {} deleted by user {}", id, requesterId);
    }

    /** objectKey 是否被其他视频记录引用:同 md5 已有对象的其他行,或直接同 objectKey 的其他行。 */
    private boolean objectKeySharedByOthers(Video video) {
        if (video.getMd5() != null) {
            Long sameMd5 = videoMapper.selectCount(new LambdaQueryWrapper<Video>()
                    .eq(Video::getMd5, video.getMd5())
                    .ne(Video::getId, video.getId())
                    .isNotNull(Video::getObjectKey));
            if (sameMd5 != null && sameMd5 > 0) {
                return true;
            }
        }
        Long sameKey = videoMapper.selectCount(new LambdaQueryWrapper<Video>()
                .eq(Video::getObjectKey, video.getObjectKey())
                .ne(Video::getId, video.getId()));
        return sameKey != null && sameKey > 0;
    }

    private void removeObject(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.warn("object remove failed, bucket={}, object={}: {}", bucket, objectKey, e.getMessage());
        }
    }

    /** 删除 bucket 下某前缀的全部对象(HLS 产物清理)。 */
    private void removePrefix(String bucket, String prefix) {
        try {
            List<io.minio.messages.DeleteObject> objects = new java.util.ArrayList<>();
            for (io.minio.Result<io.minio.messages.Item> r : minioClient.listObjects(
                    io.minio.ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .recursive(true)
                            .build())) {
                objects.add(new io.minio.messages.DeleteObject(r.get().objectName()));
            }
            if (objects.isEmpty()) {
                return;
            }
            for (io.minio.Result<io.minio.messages.DeleteError> errResult : minioClient.removeObjects(
                    io.minio.RemoveObjectsArgs.builder()
                            .bucket(bucket)
                            .objects(objects)
                            .build())) {
                log.warn("object delete failed: {}", errResult.get().objectName());
            }
        } catch (Exception e) {
            log.warn("prefix cleanup failed, bucket={}, prefix={}: {}", bucket, prefix, e.getMessage());
        }
    }

    /** 编辑投稿:仅 owner,任意状态;category/tags 未传时清空(表单整体提交语义)。 */
    public void editVideo(Long id, Long requesterId, String title, String description,
                          String category, String tags) {
        Video video = videoMapper.selectById(id);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        if (!video.getOwnerId().equals(requesterId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (title == null || title.isBlank() || title.length() > 100) {
            throw BizException.of(ErrorCode.VIDEO_TITLE_INVALID);
        }
        if (description != null && description.length() > 2000) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "简介过长");
        }
        String normalizedCategory = VideoMetaPolicy.normalizeCategory(category);
        String normalizedTags = VideoMetaPolicy.normalizeTags(tags);
        String trimmedTitle = title.trim();
        String trimmedDescription = description == null ? "" : description.trim();
        // 编辑同样过机审:标题与简介命中敏感词直接拒绝,防止绕过上传时的筛查
        if (!sensitiveWordHolder.current().screen(trimmedTitle).isEmpty()
                || !sensitiveWordHolder.current().screen(trimmedDescription).isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "内容包含违规词汇");
        }
        videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                .eq(Video::getId, id)
                .set(Video::getTitle, trimmedTitle)
                .set(Video::getDescription, trimmedDescription)
                .set(Video::getCategory, normalizedCategory)
                .set(Video::getTags, normalizedTags)
                .set(Video::getUpdatedAt, LocalDateTime.now()));
        evictListCache();
        log.info("video {} edited by user {}", id, requesterId);
    }

    public String presignedOriginalUrl(Long videoId, int expirySeconds) {
        Video video = videoMapper.selectById(videoId);
        if (video == null || video.getObjectKey() == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        try {
            return minioClient.getPresignedObjectUrl(
                    io.minio.GetPresignedObjectUrlArgs.builder()
                            .method(io.minio.http.Method.GET)
                            .bucket(MinioConfig.BUCKET_VIDEOS)
                            .object(video.getObjectKey())
                            .expiry(expirySeconds)
                            .build());
        } catch (Exception e) {
            log.error("presign failed for video {}", videoId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "获取原片地址失败");
        }
    }

    public Video requireVideo(Long id) {
        Video video = videoMapper.selectById(id);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        return video;
    }

    // ==================== 分片上传(断点续传/秒传) ====================
    //
    // 实现:每个分片是独立对象(chunks/{uploadId}/{partNumber}),浏览器凭预签名 PUT 直传 MinIO;
    // 全部到齐后 composeObject 服务端合并为 videos/{id}/original.{ext},再清理分片对象。
    // (MinIO Java SDK 未暴露 S3 multipart 高级 API,composeObject 是官方支持的合并方式。)

    private static final String CHUNK_PREFIX = "chunks/";
    private static final long STALE_UPLOAD_MS = 7L * 24 * 3600 * 1000;


    /** 初始化/恢复/秒传。md5 命中已完成视频 → 秒传;同用户同 md5 未完成会话 → 续传;否则新建会话。
     *  title/description/category/tags 供秒传分支建记录使用(普通/续传路径在 complete 时提交)。 */
    public com.toys.video.video.dto.InitUploadResponse initUpload(String fileName, long fileSize, String md5,
                                                                  Long ownerId, String title, String description,
                                                                  String category, String tags) {
        if (fileSize <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "文件大小不合法");
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw BizException.of(ErrorCode.VIDEO_TOO_LARGE);
        }
        String safeName = sanitizeFileName(fileName);
        String ext = extOf(safeName);
        if (!allowedExtsAllowed(ext)) {
            throw BizException.of(ErrorCode.VIDEO_TYPE_FORBIDDEN);
        }
        String digest = normalizeMd5(md5);

        cleanupStaleUploads();

        // 1. 秒传:同内容已有完成记录,直接复用对象
        if (digest != null) {
            Video done = videoMapper.selectOne(new LambdaQueryWrapper<Video>()
                    .eq(Video::getMd5, digest)
                    .ne(Video::getStatus, VideoStatus.UPLOADED.name())
                    .isNotNull(Video::getObjectKey)
                    .last("limit 1"));
            if (done != null) {
                Video v = new Video();
                v.setOwnerId(ownerId);
                // 秒传也采用表单元数据:标题未填才回退文件名去扩展名,文件名无有效点号时回退"未命名投稿"
                String instantTitle = title != null && !title.isBlank()
                        ? title.trim() : fileNameTitleOf(safeName);
                if (instantTitle.isEmpty() || instantTitle.length() > 100) {
                    throw BizException.of(ErrorCode.VIDEO_TITLE_INVALID);
                }
                v.setTitle(instantTitle);
                v.setDescription(description == null ? "" : description.trim());
                v.setCategory(VideoMetaPolicy.normalizeCategory(category));
                v.setTags(VideoMetaPolicy.normalizeTags(tags));
                if (v.getDescription().length() > 2000) {
                    throw BizException.of(ErrorCode.PARAM_INVALID, "简介过长");
                }
                v.setStatus(VideoStatus.AUTO_SCREENING.name());
                v.setObjectKey(done.getObjectKey());
                v.setOriginalFilename(safeName);
                v.setSizeBytes(done.getSizeBytes() != null ? done.getSizeBytes() : fileSize);
                v.setMd5(digest);
                videoMapper.insert(v);
                eventPublisher.publishUploaded(new VideoUploadedEvent(
                        v.getId(), v.getObjectKey(), ownerId, safeName,
                        v.getTitle(), v.getDescription()));
                log.info("instant upload: video {} reuses object of {}", v.getId(), done.getId());
                return new com.toys.video.video.dto.InitUploadResponse(v.getId(), null, null, List.of(), true);
            }
        }

        // 2. 断点续传:同用户同 md5 的未完成会话
        if (digest != null) {
            Video existing = videoMapper.selectOne(new LambdaQueryWrapper<Video>()
                    .eq(Video::getMd5, digest)
                    .eq(Video::getOwnerId, ownerId)
                    .eq(Video::getStatus, VideoStatus.UPLOADED.name())
                    .isNotNull(Video::getUploadId)
                    .orderByDesc(Video::getCreatedAt)
                    .last("limit 1"));
            if (existing != null) {
                long partSize = existing.getPartSize() != null ? existing.getPartSize() : UploadPolicy.MIN_PART_SIZE;
                List<Integer> doneParts = listUploadedPartNumbers(existing);
                log.info("resume upload: video {} parts done={}", existing.getId(), doneParts.size());
                return new com.toys.video.video.dto.InitUploadResponse(
                        existing.getId(), existing.getUploadId(), partSize, doneParts, false);
            }
        }

        // 3. 新建分片会话
        Video video = new Video();
        video.setOwnerId(ownerId);
        video.setTitle("(上传中) " + safeName);
        video.setDescription("");
        video.setStatus(VideoStatus.UPLOADED.name());
        video.setMd5(digest);
        video.setSizeBytes(fileSize);
        video.setPartSize(UploadPolicy.computePartSize(fileSize));
        videoMapper.insert(video);
        video.setObjectKey(video.getId() + "/original." + ext);
        video.setUploadId(java.util.UUID.randomUUID().toString().replace("-", ""));
        videoMapper.updateById(video);
        return new com.toys.video.video.dto.InitUploadResponse(
                video.getId(), video.getUploadId(), video.getPartSize(), List.of(), false);
    }

    /** 签发分片直传 MinIO 的预签名 PUT 地址(网关不经手文件字节)。 */
    public String presignPart(Long videoId, int partNumber, Long ownerId) {
        Video video = requireOwnedUpload(videoId, ownerId);
        if (partNumber < 1 || partNumber > UploadPolicy.MAX_PARTS) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "分片序号不合法");
        }
        try {
            return minioClient.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.PUT)
                    .bucket(MinioConfig.BUCKET_VIDEOS)
                    .object(chunkObject(video.getUploadId(), partNumber))
                    .expiry(3600)
                    .build());
        } catch (Exception e) {
            log.error("presign part failed, video={}, part={}", videoId, partNumber, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "签发分片地址失败");
        }
    }

    /** 全部分片上传完成后合并并提交审核。 */
    public void completeUpload(Long videoId, String title, String description,
                               String category, String tags, Long ownerId) {
        Video video = requireOwnedUpload(videoId, ownerId);
        // ①原子占用:仅当仍处于 UPLOADED 且持有本次 uploadId 时推进到 AUTO_SCREENING,
        // 两个并发 complete 只有一个能成功占用,另一个直接冲突返回
        int claimed = videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                .eq(Video::getId, videoId)
                .eq(Video::getStatus, VideoStatus.UPLOADED.name())
                .eq(Video::getUploadId, video.getUploadId())
                .set(Video::getStatus, VideoStatus.AUTO_SCREENING.name())
                .set(Video::getUpdatedAt, LocalDateTime.now()));
        if (claimed == 0) {
            throw BizException.of(ErrorCode.VIDEO_STATUS_CONFLICT, "上传会话不存在或已完成");
        }
        try {
            if (title == null || title.isBlank() || title.length() > 100) {
                throw BizException.of(ErrorCode.VIDEO_TITLE_INVALID);
            }
            if (description != null && description.length() > 2000) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "简介过长");
            }
            String normalizedCategory = VideoMetaPolicy.normalizeCategory(category);
            String normalizedTags = VideoMetaPolicy.normalizeTags(tags);
            long partSize = video.getPartSize() != null ? video.getPartSize() : UploadPolicy.MIN_PART_SIZE;
            long size = video.getSizeBytes() != null ? video.getSizeBytes() : 0;
            int expected = size > 0 ? (int) Math.ceil((double) size / partSize) : 1;

            // ②校验分片数量→compose→清理分片
            List<Integer> uploaded = listUploadedPartNumbers(video);
            if (uploaded.size() != expected) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "分片不完整: 已收 %d/%d".formatted(uploaded.size(), expected));
            }

            // 服务端合并:composeObject 按 5MiB 对齐的分片顺序拼接
            List<io.minio.ComposeSource> sources = uploaded.stream()
                    .map(p -> io.minio.ComposeSource.builder()
                            .bucket(MinioConfig.BUCKET_VIDEOS)
                            .object(chunkObject(video.getUploadId(), p))
                            .build())
                    .toList();
            try {
                minioClient.composeObject(io.minio.ComposeObjectArgs.builder()
                        .bucket(MinioConfig.BUCKET_VIDEOS)
                        .object(video.getObjectKey())
                        .sources(sources)
                        .build());
            } catch (Exception e) {
                log.error("compose chunks failed, video={}", videoId, e);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "分片合并失败");
            }

            deleteChunks(video.getUploadId(), uploaded);

            // ③显式提交表单元数据并置空 uploadId:UpdateWrapper set null 才能真正清列
            videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                    .eq(Video::getId, videoId)
                    .set(Video::getUploadId, null)
                    .set(Video::getTitle, title.trim())
                    .set(Video::getDescription, description == null ? "" : description.trim())
                    .set(Video::getCategory, normalizedCategory)
                    .set(Video::getTags, normalizedTags)
                    .set(Video::getUpdatedAt, LocalDateTime.now()));

            eventPublisher.publishUploaded(new VideoUploadedEvent(
                    video.getId(), video.getObjectKey(), video.getOwnerId(), video.getOriginalFilename(),
                    title.trim(), description == null ? "" : description.trim()));
            log.info("video {} chunks merged ({} parts), submitted for moderation", videoId, uploaded.size());
        } catch (RuntimeException e) {
            // ④⑤占用后任何一步失败(校验/合并/事件):回退到 UPLOADED 释放会话,再抛原异常
            log.error("complete upload failed, release session for video {}", videoId, e);
            videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                    .eq(Video::getId, videoId)
                    .eq(Video::getStatus, VideoStatus.AUTO_SCREENING.name())
                    .set(Video::getStatus, VideoStatus.UPLOADED.name())
                    .set(Video::getUpdatedAt, LocalDateTime.now()));
            throw e;
        }
    }

    private Video requireOwnedUpload(Long videoId, Long ownerId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        if (!video.getOwnerId().equals(ownerId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (!VideoStatus.UPLOADED.name().equals(video.getStatus()) || video.getUploadId() == null) {
            throw BizException.of(ErrorCode.VIDEO_STATUS_CONFLICT, "上传会话不存在或已完成");
        }
        return video;
    }

    private String chunkObject(String uploadId, int partNumber) {
        return CHUNK_PREFIX + uploadId + "/" + partNumber;
    }

    /** 已上传分片号:列出 chunks/{uploadId}/ 前缀下的对象名。 */
    private List<Integer> listUploadedPartNumbers(Video video) {
        try {
            List<Integer> parts = new java.util.ArrayList<>();
            for (io.minio.Result<io.minio.messages.Item> r : minioClient.listObjects(
                    io.minio.ListObjectsArgs.builder()
                            .bucket(MinioConfig.BUCKET_VIDEOS)
                            .prefix(CHUNK_PREFIX + video.getUploadId() + "/")
                            .recursive(true)
                            .build())) {
                String name = r.get().objectName();
                parts.add(Integer.parseInt(name.substring(name.lastIndexOf('/') + 1)));
            }
            java.util.Collections.sort(parts);
            return parts;
        } catch (Exception e) {
            log.warn("list chunks failed, treat as empty: {}", e.getMessage());
            return List.of();
        }
    }

    private void deleteChunks(String uploadId, List<Integer> parts) {
        try {
            List<io.minio.messages.DeleteObject> objects = parts.stream()
                    .map(p -> new io.minio.messages.DeleteObject(chunkObject(uploadId, p)))
                    .toList();
            for (io.minio.Result<io.minio.messages.DeleteError> errResult : minioClient.removeObjects(
                    io.minio.RemoveObjectsArgs.builder()
                            .bucket(MinioConfig.BUCKET_VIDEOS)
                            .objects(objects)
                            .build())) {
                log.warn("chunk delete failed: {}", errResult.get().objectName());
            }
        } catch (Exception e) {
            log.warn("chunk cleanup failed: {}", e.getMessage());
        }
    }

    /** 清洗文件名:剥离路径分隔符与 "..",防路径穿越(文件名会进日志与 originalFilename 字段)。 */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        return fileName.replace("/", "").replace("\\", "").replace("..", "");
    }

    /** 文件名去扩展名作为标题:无点号或点在首位(无有效主干)时回退"未命名投稿"。 */
    static String fileNameTitleOf(String safeName) {
        int dot = safeName.lastIndexOf('.');
        if (dot > 0) {
            String base = safeName.substring(0, dot).trim();
            if (!base.isEmpty()) {
                return base;
            }
        }
        return "未命名投稿";
    }

    /** md5 归一:空视为 null;非空必须为 32 位十六进制。 */
    private String normalizeMd5(String md5) {
        if (md5 == null || md5.isBlank()) {
            return null;
        }
        String trimmed = md5.trim();
        if (!trimmed.matches("^[a-fA-F0-9]{32}$")) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "md5 格式不合法");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /** 清理 7 天未完成的孤儿会话,避免半截分片永久占用存储。 */
    private void cleanupStaleUploads() {
        List<Video> stale = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                .eq(Video::getStatus, VideoStatus.UPLOADED.name())
                .isNotNull(Video::getUploadId)
                .lt(Video::getCreatedAt, LocalDateTime.now().minusSeconds(STALE_UPLOAD_MS / 1000))
                .last("limit 20"));
        for (Video video : stale) {
            deleteChunks(video.getUploadId(), listUploadedPartNumbers(video));
            videoMapper.deleteById(video.getId());
            log.info("cleaned stale upload session, video={}", video.getId());
        }
    }

    /** 一步上传失败回退:删除 videos 行并尽力清理原片对象,不留无法推进的死记录。 */
    private void discardFailedUpload(Long id, String objectKey) {
        videoMapper.deleteById(id);
        if (objectKey != null) {
            removeObject(MinioConfig.BUCKET_VIDEOS, objectKey);
        }
    }

    private VideoStatus parseStatus(String s) {
        try {
            return VideoStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "未知状态: " + s);
        }
    }

    private PageResult<VideoCard> toCards(IPage<Video> p) {
        return toCards(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 按给定顺序组装卡片并填充 up 主昵称(收藏/历史列表复用)。 */
    private PageResult<VideoCard> toCards(List<Video> videos, long total, long page, long size) {
        List<Long> ownerIds = videos.stream().map(Video::getOwnerId).distinct().toList();
        Map<Long, String> names = ownerIds.isEmpty() ? Map.of() : fetchOwnerNames(ownerIds);
        List<VideoCard> cards = videos.stream()
                .map(v -> new VideoCard(v.getId(), v.getTitle(), posterOf(v), v.getDurationSec(),
                        v.getPlayCount(), v.getOwnerId(), names.get(v.getOwnerId()),
                        v.getStatus(), v.getNote(), v.getCategory(), v.getTags(), v.getPublishedAt()))
                .toList();
        return new PageResult<>(cards, total, page, size);
    }

    private Map<Long, Video> videosByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return videoMapper.selectList(new LambdaQueryWrapper<Video>().in(Video::getId, ids)).stream()
                .collect(Collectors.toMap(Video::getId, Function.identity(), (a, b) -> a));
    }

    /** UP 主展示名:nickname 非空优先,回退 username;Feign 失败时以「用户{id}」兜底。 */
    private Map<Long, String> fetchOwnerNames(List<Long> ownerIds) {
        try {
            return userInternalClient.batch(ownerIds).data().stream()
                    .collect(Collectors.toMap(UserInternalClient.UserBrief::id,
                            brief -> brief.nickname() != null && !brief.nickname().isBlank()
                                    ? brief.nickname() : brief.username(),
                            (a, b) -> a));
        } catch (Exception e) {
            log.warn("fetch owner names failed: {}", e.getMessage());
            return ownerIds.stream().collect(Collectors.toMap(Function.identity(), id -> "用户" + id, (a, b) -> a));
        }
    }

    private VideoDetail toDetail(Video v, Long requesterId) {
        boolean published = VideoStatus.PUBLISHED.name().equals(v.getStatus());
        String ownerName = fetchOwnerNames(List.of(v.getOwnerId())).getOrDefault(v.getOwnerId(), "用户" + v.getOwnerId());
        // 登录用户才查互动状态;断点续播位置是每个登录用户自己的历史
        boolean liked = requesterId != null && interactionService.likedByMe(v.getId(), requesterId);
        boolean favorited = requesterId != null && interactionService.favoritedByMe(v.getId(), requesterId);
        Double resumePosition = requesterId != null
                ? historyService.positionOf(v.getId(), requesterId) : null;
        return new VideoDetail(v.getId(), v.getTitle(), v.getDescription(), posterOf(v),
                published ? "/media/hls/" + v.getId() + "/master.m3u8" : null,
                v.getDurationSec(), v.getPlayCount(), v.getOwnerId(), ownerName,
                v.getStatus(), v.getNote(), v.getCategory(), VideoMetaPolicy.parseTags(v.getTags()),
                v.getOriginalFilename(), v.getSizeBytes(),
                v.getCreatedAt(), v.getPublishedAt(),
                liked, v.getLikeCount(), favorited, resumePosition);
    }

    private String posterOf(Video v) {
        return VideoStatus.PUBLISHED.name().equals(v.getStatus())
                ? "/media/hls/" + v.getId() + "/poster.jpg" : null;
    }

    private String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw BizException.of(ErrorCode.VIDEO_TYPE_FORBIDDEN);
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean allowedExtsAllowed(String ext) {
        for (String e : allowedExts.split(",")) {
            if (e.trim().equalsIgnoreCase(ext)) {
                return true;
            }
        }
        return false;
    }
}
