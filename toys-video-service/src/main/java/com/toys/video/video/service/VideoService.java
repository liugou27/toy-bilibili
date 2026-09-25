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
import com.toys.video.video.discovery.recommend.RecommendGateway;
import com.toys.video.video.discovery.search.SearchGateway;
import com.toys.video.video.dto.VideoCard;
import com.toys.video.video.dto.VideoDetail;
import com.toys.video.video.entity.Video;
import com.toys.video.video.mapper.VideoMapper;
import com.toys.video.video.mq.VideoEventPublisher;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final Map<VideoStatus, Set<VideoStatus>> ALLOWED_TRANSITIONS = Map.of(
            VideoStatus.AUTO_SCREENING, Set.of(VideoStatus.UNDER_REVIEW, VideoStatus.REJECTED),
            VideoStatus.UNDER_REVIEW, Set.of(VideoStatus.APPROVED, VideoStatus.REJECTED),
            VideoStatus.APPROVED, Set.of(VideoStatus.TRANSCODING),
            VideoStatus.TRANSCODING, Set.of(VideoStatus.PUBLISHED, VideoStatus.TRANSCODE_FAILED)
    );

    private final VideoMapper videoMapper;
    private final VideoEventPublisher eventPublisher;
    private final io.minio.MinioClient minioClient;
    private final SearchGateway searchGateway;
    private final RecommendGateway recommendGateway;
    private final UserInternalClient userInternalClient;

    @Value("${toys.upload.allowed-exts:mp4,mkv,mov,avi,flv}")
    private String allowedExts;

    public com.toys.video.video.dto.UploadResponse upload(MultipartFile file, String title, String description,
                                                          Long ownerId) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "文件不能为空");
        }
        if (title == null || title.isBlank() || title.length() > 100) {
            throw BizException.of(ErrorCode.VIDEO_TITLE_INVALID);
        }
        if (description != null && description.length() > 2000) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "简介过长");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extOf(filename);
        if (!allowedExtsAllowed(ext)) {
            throw BizException.of(ErrorCode.VIDEO_TYPE_FORBIDDEN);
        }

        Video video = new Video();
        video.setOwnerId(ownerId);
        video.setTitle(title.trim());
        video.setDescription(description == null ? "" : description.trim());
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
                    video.getId(), objectKey, ownerId, filename));
        } catch (Exception e) {
            log.error("publish VIDEO_UPLOADED failed, videoId={}, reverting status", video.getId(), e);
            revertToUploaded(video.getId());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "任务提交失败,请重试");
        }
        return new com.toys.video.video.dto.UploadResponse(video.getId(), video.getStatus());
    }

    public PageResult<VideoCard> publishedPage(long page, long size, String keyword) {
        IPage<Video> p = (keyword == null || keyword.isBlank())
                ? recommendGateway.recommend(page, size)
                : searchGateway.searchPublished(keyword, page, size);
        return toCards(p);
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
        return toDetail(video, ownerOrAdmin ? video.getOwnerId() : null);
    }

    public PageResult<VideoCard> mine(Long ownerId, long page, long size) {
        IPage<Video> p = videoMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getOwnerId, ownerId)
                        .orderByDesc(Video::getCreatedAt));
        return toCards(p);
    }

    /** 机审/转码服务请求推进状态机; UNDER_REVIEW→APPROVED 会同步发出转码事件。 */
    public void updateStatusInternal(Long id, com.toys.video.api.feign.VideoInternalClient.InternalStatusUpdate update) {
        VideoStatus target = parseStatus(update.target());
        Video video = videoMapper.selectById(id);
        if (video == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
        VideoStatus current = VideoStatus.valueOf(video.getStatus());
        Set<VideoStatus> allowed = ALLOWED_TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new BizException(ErrorCode.VIDEO_STATUS_CONFLICT,
                    "非法状态流转: %s -> %s".formatted(current, target));
        }
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
        if (target == VideoStatus.APPROVED) {
            eventPublisher.publishApproved(new VideoApprovedEvent(video.getId(), video.getObjectKey()));
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
        int rows = videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                .eq(Video::getId, id)
                .eq(Video::getStatus, VideoStatus.TRANSCODE_FAILED.name())
                .set(Video::getStatus, VideoStatus.APPROVED.name())
                .set(Video::getNote, null)
                .set(Video::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ErrorCode.VIDEO_STATUS_CONFLICT);
        }
        eventPublisher.publishApproved(new VideoApprovedEvent(id, video.getObjectKey()));
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

    private void revertToUploaded(Long id) {
        videoMapper.update(null, new LambdaUpdateWrapper<Video>()
                .eq(Video::getId, id)
                .set(Video::getStatus, VideoStatus.UPLOADED.name())
                .set(Video::getUpdatedAt, LocalDateTime.now()));
    }

    private VideoStatus parseStatus(String s) {
        try {
            return VideoStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "未知状态: " + s);
        }
    }

    private PageResult<VideoCard> toCards(IPage<Video> p) {
        List<Long> ownerIds = p.getRecords().stream().map(Video::getOwnerId).distinct().toList();
        Map<Long, String> names = ownerIds.isEmpty() ? Map.of() : fetchOwnerNames(ownerIds);
        List<VideoCard> cards = p.getRecords().stream()
                .map(v -> new VideoCard(v.getId(), v.getTitle(), posterOf(v), v.getDurationSec(),
                        v.getPlayCount(), v.getOwnerId(), names.get(v.getOwnerId()),
                        v.getStatus(), v.getNote(), v.getPublishedAt()))
                .toList();
        return new PageResult<>(cards, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private Map<Long, String> fetchOwnerNames(List<Long> ownerIds) {
        try {
            return userInternalClient.batch(ownerIds).data().stream()
                    .collect(Collectors.toMap(UserInternalClient.UserBrief::id,
                            UserInternalClient.UserBrief::username, (a, b) -> a));
        } catch (Exception e) {
            log.warn("fetch owner names failed: {}", e.getMessage());
            return ownerIds.stream().collect(Collectors.toMap(Function.identity(), id -> "用户" + id, (a, b) -> a));
        }
    }

    private VideoDetail toDetail(Video v, Long requesterId) {
        boolean published = VideoStatus.PUBLISHED.name().equals(v.getStatus());
        String ownerName = fetchOwnerNames(List.of(v.getOwnerId())).getOrDefault(v.getOwnerId(), "用户" + v.getOwnerId());
        return new VideoDetail(v.getId(), v.getTitle(), v.getDescription(), posterOf(v),
                published ? "/media/hls/" + v.getId() + "/master.m3u8" : null,
                v.getDurationSec(), v.getPlayCount(), v.getOwnerId(), ownerName,
                v.getStatus(), v.getNote(), v.getOriginalFilename(), v.getSizeBytes(),
                v.getCreatedAt(), v.getPublishedAt());
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
