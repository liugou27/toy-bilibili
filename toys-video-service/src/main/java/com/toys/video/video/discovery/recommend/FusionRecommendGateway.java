package com.toys.video.video.discovery.recommend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.api.enums.VideoStatus;
import com.toys.video.video.entity.Video;
import com.toys.video.video.entity.VideoHistory;
import com.toys.video.video.mapper.VideoHistoryMapper;
import com.toys.video.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 融合推荐:三路召回各取前 N,各路分数 min-max 归一化后加权融合,
 * 同视频去重后按融合分降序分页,结果不足用最新发布补齐到页大小;只输出 PUBLISHED。
 * 权重:登录 热度0.4 / 共现0.4 / UP偏好0.2;匿名 热度0.7 / 全站共现0.3。
 */
@Component
@RequiredArgsConstructor
public class FusionRecommendGateway implements RecommendGateway {

    /** 每路召回条数。 */
    static final int RECALL_LIMIT = 50;
    static final double W_HOT_USER = 0.4;
    static final double W_COOCUR_USER = 0.4;
    static final double W_UP_USER = 0.2;
    static final double W_HOT_ANON = 0.7;
    static final double W_COOCUR_ANON = 0.3;

    private final VideoMapper videoMapper;
    private final VideoHistoryMapper historyMapper;

    /** 单路召回分数项:score 为通道内名次分(第 1 名 LIMIT,依次递减),与通道排序单调一致。 */
    record Scored(long videoId, double score) {
    }

    @Override
    public IPage<Video> recommend(long page, long size) {
        return recommendForUser(null, page, size);
    }

    @Override
    public IPage<Video> recommendForUser(Long userId, long page, long size) {
        Map<Long, Video> pool = new LinkedHashMap<>();
        List<Scored> hot = recallHot(RECALL_LIMIT, pool);
        List<Scored> cooccur;
        List<Scored> upPref;
        double hotW;
        double coocurW;
        double upW;
        if (userId == null) {
            // 匿名:无个人历史可用,共现退化为全站共现,无 UP 偏好
            cooccur = recallGlobalCooccur(RECALL_LIMIT, pool);
            upPref = List.of();
            hotW = W_HOT_ANON;
            coocurW = W_COOCUR_ANON;
            upW = 0;
        } else {
            cooccur = recallCooccur(latestWatchedVideoId(userId), RECALL_LIMIT, pool);
            upPref = recallUpPreference(userId, RECALL_LIMIT, pool);
            hotW = W_HOT_USER;
            coocurW = W_COOCUR_USER;
            upW = W_UP_USER;
        }
        List<Scored> fused = fuse(hot, cooccur, upPref, hotW, coocurW, upW);
        return paginate(fused, pool, page, size);
    }

    @Override
    public IPage<Video> related(Long videoId, int size) {
        Video seed = videoMapper.selectById(videoId);
        if (seed == null || !VideoStatus.PUBLISHED.name().equals(seed.getStatus())) {
            return new Page<>(1, size, 0);
        }
        Map<Long, Video> pool = new LinkedHashMap<>();
        pool.put(seed.getId(), seed);
        List<Scored> coocur = recallCooccur(videoId, size, pool);
        List<Video> records = new ArrayList<>(coocur.stream()
                .map(s -> pool.get(s.videoId()))
                .filter(Objects::nonNull)
                .toList());
        // 共现不足补热度(排除种子与已入选)
        if (records.size() < size) {
            List<Long> picked = records.stream().map(Video::getId).toList();
            for (Video v : videoMapper.selectHotVideos(size + records.size())) {
                if (records.size() >= size) {
                    break;
                }
                if (v.getId().equals(seed.getId()) || picked.contains(v.getId())) {
                    continue;
                }
                records.add(v);
            }
        }
        Page<Video> result = new Page<>(1, size, records.size());
        result.setRecords(records);
        return result;
    }

    // ==================== 召回通道 ====================

    /** 热度路:hot 降序的原生 SQL 结果按名次给分,只含 PUBLISHED。 */
    private List<Scored> recallHot(int limit, Map<Long, Video> pool) {
        List<Video> videos = videoMapper.selectHotVideos(limit);
        List<Scored> scored = new ArrayList<>();
        int rank = limit;
        for (Video v : videos) {
            pool.put(v.getId(), v);
            scored.add(new Scored(v.getId(), rank--));
        }
        return scored;
    }

    /** 共现路:看过种子视频的人还看过什么;种子为空(无观看历史)则该路为空。 */
    private List<Scored> recallCooccur(Long seedVideoId, int limit, Map<Long, Video> pool) {
        if (seedVideoId == null) {
            return List.of();
        }
        return scoreIds(videoMapper.selectCooccurVideoIds(seedVideoId, limit), limit, pool);
    }

    /** 匿名共现路:全站同用户共现次数最高的视频。 */
    private List<Scored> recallGlobalCooccur(int limit, Map<Long, Video> pool) {
        return scoreIds(videoMapper.selectGlobalCooccurVideoIds(limit), limit, pool);
    }

    /** UP 偏好路:最常看的 UP 主 TOP3 的 PUBLISHED 热门视频(播放量降序)。 */
    private List<Scored> recallUpPreference(Long userId, int limit, Map<Long, Video> pool) {
        List<Long> ownerIds = videoMapper.selectTopOwnerIds(userId);
        if (ownerIds.isEmpty()) {
            return List.of();
        }
        List<Video> videos = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                .in(Video::getOwnerId, ownerIds)
                .eq(Video::getStatus, VideoStatus.PUBLISHED.name())
                .orderByDesc(Video::getPlayCount)
                .last("limit " + limit));
        List<Scored> scored = new ArrayList<>();
        int rank = limit;
        for (Video v : videos) {
            pool.put(v.getId(), v);
            scored.add(new Scored(v.getId(), rank--));
        }
        return scored;
    }

    /** id 序 → 装载缺失实体并只保留 PUBLISHED → 名次分。 */
    private List<Scored> scoreIds(List<Long> ids, int limit, Map<Long, Video> pool) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> missing = ids.stream().filter(id -> !pool.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            videoMapper.selectList(new LambdaQueryWrapper<Video>().in(Video::getId, missing)).stream()
                    .filter(v -> VideoStatus.PUBLISHED.name().equals(v.getStatus()))
                    .forEach(v -> pool.put(v.getId(), v));
        }
        List<Scored> scored = new ArrayList<>();
        int rank = limit;
        for (Long id : ids) {
            if (pool.containsKey(id)) {
                scored.add(new Scored(id, rank));
            }
            rank--;
        }
        return scored;
    }

    /** 登录用户最近观看的视频,作为共现种子。 */
    private Long latestWatchedVideoId(Long userId) {
        VideoHistory latest = historyMapper.selectOne(new LambdaQueryWrapper<VideoHistory>()
                .eq(VideoHistory::getUserId, userId)
                .orderByDesc(VideoHistory::getUpdatedAt)
                .last("limit 1"));
        return latest == null ? null : latest.getVideoId();
    }

    // ==================== 融合与分页 ====================

    /**
     * 融合打分(纯函数,便于单测):各路 min-max 归一化后加权求和,
     * 同视频只保留一条(融合分为各路加权归一分之和,多路同时命中自然提升排名),
     * 按融合分降序、并列时 videoId 升序输出。
     */
    static List<Scored> fuse(List<Scored> hot, List<Scored> coocur, List<Scored> upPref,
                             double hotWeight, double coocurWeight, double upWeight) {
        Map<Long, Double> fused = new HashMap<>();
        accumulate(fused, normalize(hot), hotWeight);
        accumulate(fused, normalize(coocur), coocurWeight);
        accumulate(fused, normalize(upPref), upWeight);
        return fused.entrySet().stream()
                .map(e -> new Scored(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(Scored::videoId))
                .toList();
    }

    /** min-max 归一化:空路无贡献;单元素(极差为 0)整路按满分 1.0 计。 */
    static Map<Long, Double> normalize(List<Scored> channel) {
        if (channel.isEmpty()) {
            return Map.of();
        }
        double min = channel.stream().mapToDouble(Scored::score).min().orElse(0);
        double max = channel.stream().mapToDouble(Scored::score).max().orElse(0);
        Map<Long, Double> norm = new LinkedHashMap<>();
        for (Scored s : channel) {
            norm.put(s.videoId(), max == min ? 1.0 : (s.score() - min) / (max - min));
        }
        return norm;
    }

    private static void accumulate(Map<Long, Double> fused, Map<Long, Double> normalized, double weight) {
        normalized.forEach((id, n) -> fused.merge(id, weight * n, Double::sum));
    }

    /** 融合序分页;不足页容量用最新发布补齐(排除已入选,追加序确定,跨页不重复)。 */
    private IPage<Video> paginate(List<Scored> fused, Map<Long, Video> pool, long page, long size) {
        List<Scored> ranked = fused;
        long needed = page * size;
        if (ranked.size() < needed) {
            ranked = backfillLatest(ranked, (int) (needed - ranked.size()), pool);
        }
        long from = (page - 1) * size;
        List<Video> records = from >= ranked.size() ? List.of()
                : ranked.subList((int) from, (int) Math.min(from + size, ranked.size())).stream()
                        .map(s -> pool.get(s.videoId()))
                        .filter(Objects::nonNull)
                        .toList();
        Page<Video> result = new Page<>(page, size, ranked.size());
        result.setRecords(records);
        return result;
    }

    private List<Scored> backfillLatest(List<Scored> fused, int count, Map<Long, Video> pool) {
        if (count <= 0) {
            return fused;
        }
        LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<Video>()
                .eq(Video::getStatus, VideoStatus.PUBLISHED.name())
                .orderByDesc(Video::getPublishedAt)
                .last("limit " + count);
        List<Long> exclude = fused.stream().map(Scored::videoId).toList();
        if (!exclude.isEmpty()) {
            wrapper.notIn(Video::getId, exclude);
        }
        List<Scored> ranked = new ArrayList<>(fused);
        java.util.Set<Long> present = new java.util.HashSet<>(exclude);
        for (Video v : videoMapper.selectList(wrapper)) {
            // 防御性去重:SQL 已排除已入选 id,并发/兜底场景下仍跳过重复
            if (!present.add(v.getId())) {
                continue;
            }
            pool.put(v.getId(), v);
            ranked.add(new Scored(v.getId(), 0));
        }
        return ranked;
    }
}
