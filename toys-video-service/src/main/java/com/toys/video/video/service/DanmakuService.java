package com.toys.video.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.text.SensitiveWordFilter;
import com.toys.video.video.dto.DanmakuItem;
import com.toys.video.video.entity.Danmaku;
import com.toys.video.video.mapper.DanmakuMapper;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 弹幕领域服务:danmaku 的唯一写入方。
 * 发弹幕必须通过敏感词筛查,命中即拒绝;弹幕不可删除,视频删除时级联清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DanmakuService {

    /** 弹幕正文长度上限,与表字段 VARCHAR(100) 一致。 */
    static final int MAX_CONTENT_LENGTH = 100;

    /** 单视频弹幕下发上限,防止刷屏导致响应过大。 */
    private static final long MAX_RETURN = 2000;

    private final DanmakuMapper danmakuMapper;
    private final VideoMapper videoMapper;
    private final SensitiveWordFilter sensitiveWordFilter;

    /** 发弹幕:视频必须存在,timeSec≥0,正文非空、≤100 字且不命中敏感词。 */
    public DanmakuItem post(Long videoId, Long userId, Double timeSec, String content) {
        // 参数校验先行:无效输入不触发 DB 查询
        if (timeSec == null || timeSec < 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "播放位置不合法");
        }
        requireVideo(videoId);
        Danmaku danmaku = new Danmaku();
        danmaku.setVideoId(videoId);
        danmaku.setUserId(userId);
        danmaku.setTimeSec(timeSec);
        danmaku.setContent(validateContent(content));
        danmakuMapper.insert(danmaku);
        return new DanmakuItem(danmaku.getId(), danmaku.getTimeSec(), danmaku.getContent());
    }

    /** 全量弹幕:按播放位置正序,上限 2000 条。 */
    public List<DanmakuItem> list(Long videoId) {
        return danmakuMapper.selectList(new LambdaQueryWrapper<Danmaku>()
                        .eq(Danmaku::getVideoId, videoId)
                        .orderByAsc(Danmaku::getTimeSec)
                        .last("limit " + MAX_RETURN))
                .stream()
                .map(d -> new DanmakuItem(d.getId(), d.getTimeSec(), d.getContent()))
                .toList();
    }

    /** 视频删除时级联清理弹幕。 */
    public void deleteByVideo(Long videoId) {
        danmakuMapper.delete(new LambdaQueryWrapper<Danmaku>()
                .eq(Danmaku::getVideoId, videoId));
    }

    /** 正文校验:非空、≤100 字,命中敏感词即拒绝;返回 trim 后正文。 */
    String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "弹幕内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "弹幕不能超过" + MAX_CONTENT_LENGTH + "字");
        }
        String trimmed = content.trim();
        if (!sensitiveWordFilter.screen(trimmed).isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "内容包含违规词汇");
        }
        return trimmed;
    }

    private void requireVideo(Long videoId) {
        if (videoMapper.selectById(videoId) == null) {
            throw BizException.of(ErrorCode.VIDEO_NOT_FOUND);
        }
    }
}
