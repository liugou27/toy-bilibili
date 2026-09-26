package com.toys.video.video.controller;

import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.dto.CommentItem;
import com.toys.video.video.dto.DanmakuItem;
import com.toys.video.video.dto.InitUploadResponse;
import com.toys.video.video.dto.UploadResponse;
import com.toys.video.video.dto.VideoCard;
import com.toys.video.video.dto.VideoDetail;
import com.toys.video.video.service.CommentService;
import com.toys.video.video.service.DanmakuService;
import com.toys.video.video.service.InteractionService;
import com.toys.video.video.service.PlayCountService;
import com.toys.video.video.service.VideoService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;
    private final PlayCountService playCountService;
    private final InteractionService interactionService;
    private final CommentService commentService;
    private final DanmakuService danmakuService;

    /** 上传(multipart):file + title + description。小文件一步上传。 */
    @PostMapping
    public R<UploadResponse> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("title") String title,
                                    @RequestParam(value = "description", required = false) String description) {
        Long userId = requireUser();
        return R.ok(videoService.upload(file, title, description, userId));
    }

    // ==================== 分片上传(大文件/断点续传/秒传) ====================

    /** 初始化:返回 uploadId 与已完成分片;秒传时 instant=true,videoId 直接可用。 */
    @PostMapping("/upload/init")
    public R<InitUploadResponse> initUpload(@jakarta.validation.Valid @RequestBody InitUploadRequest req) {
        Long userId = requireUser();
        return R.ok(videoService.initUpload(req.fileName(), req.fileSize(), req.md5(), userId));
    }

    /** 获取第 partNumber 片直传 MinIO 的预签名地址。 */
    @GetMapping("/upload/{videoId}/presign/{partNumber}")
    public R<String> presignPart(@PathVariable Long videoId, @PathVariable int partNumber) {
        Long userId = requireUser();
        return R.ok(videoService.presignPart(videoId, partNumber, userId));
    }

    /** 全部分片上传完成后合并并提交审核。 */
    @PostMapping("/upload/{videoId}/complete")
    public R<Void> completeUpload(@PathVariable Long videoId,
                                  @RequestBody CompleteUploadRequest req) {
        Long userId = requireUser();
        videoService.completeUpload(videoId, req.title(), req.description(), userId);
        return R.ok();
    }

    public record InitUploadRequest(
            @jakarta.validation.constraints.NotBlank(message = "文件名不能为空") String fileName,
            @jakarta.validation.constraints.NotNull(message = "文件大小不能为空") Long fileSize,
            String md5) {
    }

    public record CompleteUploadRequest(
            @jakarta.validation.constraints.NotBlank(message = "标题不能为空") String title,
            String description) {
    }

    @GetMapping
    public R<PageResult<VideoCard>> list(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "12") long size,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(videoService.publishedPage(page, Math.min(size, 50), keyword));
    }

    @GetMapping("/{id}")
    public R<VideoDetail> detail(@PathVariable Long id) {
        return R.ok(videoService.detail(id, UserContext.userId(), UserContext.userRole()));
    }

    /** 播放计数:同客户端(X-Forwarded-For 首段/remoteAddr + 用户)24h 内只计一次。 */
    @PostMapping("/{id}/play")
    public R<Void> play(@PathVariable Long id, HttpServletRequest request) {
        playCountService.recordPlay(id, PlayCountService.clientKeyOf(
                request.getHeader("X-Forwarded-For"), request.getRemoteAddr(), UserContext.userId()));
        return R.ok();
    }

    // ==================== 点赞 / 收藏 ====================

    /** 点赞:重复点赞幂等。 */
    @PostMapping("/{id}/like")
    public R<Void> like(@PathVariable Long id) {
        interactionService.like(id, requireUser());
        return R.ok();
    }

    /** 取消点赞:未点赞时幂等无操作。 */
    @DeleteMapping("/{id}/like")
    public R<Void> unlike(@PathVariable Long id) {
        interactionService.unlike(id, requireUser());
        return R.ok();
    }

    /** 收藏:重复收藏幂等。 */
    @PostMapping("/{id}/favorite")
    public R<Void> favorite(@PathVariable Long id) {
        interactionService.favorite(id, requireUser());
        return R.ok();
    }

    /** 取消收藏:未收藏时幂等无操作。 */
    @DeleteMapping("/{id}/favorite")
    public R<Void> unfavorite(@PathVariable Long id) {
        interactionService.unfavorite(id, requireUser());
        return R.ok();
    }

    /** 删除投稿:owner 或 ADMIN,任何状态。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        videoService.deleteVideo(id, requireUser(), UserContext.userRole());
        return R.ok();
    }

    /** 编辑投稿:仅 owner,任意状态。 */
    @PatchMapping("/{id}")
    public R<Void> edit(@PathVariable Long id,
                        @jakarta.validation.Valid @RequestBody EditVideoRequest req) {
        videoService.editVideo(id, requireUser(), req.title(), req.description());
        return R.ok();
    }

    public record EditVideoRequest(
            @jakarta.validation.constraints.NotBlank(message = "标题不能为空")
            @jakarta.validation.constraints.Size(max = 100, message = "标题不能超过100字") String title,
            @jakarta.validation.constraints.Size(max = 2000, message = "简介不能超过2000字") String description) {
    }

    @PostMapping("/{id}/retry")
    public R<Void> retry(@PathVariable Long id) {
        Long userId = requireUser();
        videoService.retryTranscode(id, userId, UserContext.userRole());
        return R.ok();
    }

    // ==================== 评论 ====================

    /** 发评论:需登录,正文非空、≤500 字且不命中敏感词。 */
    @PostMapping("/{id}/comments")
    public R<CommentItem> postComment(@PathVariable Long id,
                                      @jakarta.validation.Valid @RequestBody CommentRequest req) {
        return R.ok(commentService.post(id, requireUser(), req.content()));
    }

    public record CommentRequest(
            @jakarta.validation.constraints.NotBlank(message = "评论内容不能为空")
            @jakarta.validation.constraints.Size(max = 500, message = "评论不能超过500字") String content) {
    }

    /** 评论分页:按发表时间倒序,公开可读。 */
    @GetMapping("/{id}/comments")
    public R<PageResult<CommentItem>> comments(@PathVariable Long id,
                                               @RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size) {
        return R.ok(commentService.page(id, page, Math.min(size, 50)));
    }

    /** 删除评论:仅评论作者本人,不是视频 owner。 */
    @DeleteMapping("/{id}/comments/{commentId}")
    public R<Void> deleteComment(@PathVariable Long id, @PathVariable Long commentId) {
        commentService.delete(id, commentId, requireUser());
        return R.ok();
    }

    // ==================== 弹幕 ====================

    /** 发弹幕:需登录,timeSec≥0,正文非空、≤100 字且不命中敏感词。 */
    @PostMapping("/{id}/danmaku")
    public R<DanmakuItem> postDanmaku(@PathVariable Long id,
                                      @jakarta.validation.Valid @RequestBody DanmakuRequest req) {
        return R.ok(danmakuService.post(id, requireUser(), req.timeSec(), req.content()));
    }

    public record DanmakuRequest(
            @jakarta.validation.constraints.NotNull(message = "播放位置不能为空") Double timeSec,
            @jakarta.validation.constraints.NotBlank(message = "弹幕内容不能为空")
            @jakarta.validation.constraints.Size(max = 100, message = "弹幕不能超过100字") String content) {
    }

    /** 全量弹幕:按播放位置正序,公开可读,上限 2000 条。 */
    @GetMapping("/{id}/danmaku")
    public R<List<DanmakuItem>> danmaku(@PathVariable Long id) {
        return R.ok(danmakuService.list(id));
    }

    private Long requireUser() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
