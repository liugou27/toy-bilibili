package com.toys.video.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.toys.video.video.entity.Video;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface VideoMapper extends BaseMapper<Video> {

    /** 标题 trigram 搜索:ILIKE 子串命中或 % 相似度命中,按相似度、播放量排序。 */
    @Select("""
            SELECT * FROM videos
            WHERE status = 'PUBLISHED'
              AND (title ILIKE '%' || #{keyword} || '%' OR title % #{keyword})
            ORDER BY similarity(title, #{keyword}) DESC, play_count DESC
            """)
    IPage<Video> searchByTrgm(IPage<Video> page, @Param("keyword") String keyword);

    /**
     * 热度召回,仅 PUBLISHED:
     * hot = (log10(play_count+1)*100 + like_count*30) / power(发布至今小时数+2, 1.5),按 hot 降序。
     */
    @Select("""
            SELECT * FROM videos
            WHERE status = 'PUBLISHED' AND published_at IS NOT NULL
            ORDER BY (log10(play_count + 1) * 100 + like_count * 30)
                     / power(greatest(extract(epoch FROM (now() - published_at)) / 3600.0, 0) + 2, 1.5) DESC,
                     play_count DESC
            LIMIT #{limit}
            """)
    List<Video> selectHotVideos(@Param("limit") int limit);

    /** 共现召回:看过 seedVideo 的人还看过什么,按共现次数降序。 */
    @Select("""
            SELECT ph.video_id
            FROM play_histories ph
            WHERE ph.user_id IN (SELECT user_id FROM play_histories WHERE video_id = #{videoId})
              AND ph.video_id <> #{videoId}
            GROUP BY ph.video_id
            ORDER BY count(*) DESC
            LIMIT #{limit}
            """)
    List<Long> selectCooccurVideoIds(@Param("videoId") Long videoId, @Param("limit") int limit);

    /** 全站共现召回(匿名用):同用户看过的视频两两共现,按共现次数降序。 */
    @Select("""
            SELECT b.video_id
            FROM play_histories a
            JOIN play_histories b ON b.user_id = a.user_id AND b.video_id <> a.video_id
            GROUP BY b.video_id
            ORDER BY count(*) DESC
            LIMIT #{limit}
            """)
    List<Long> selectGlobalCooccurVideoIds(@Param("limit") int limit);

    /** 各分区 PUBLISHED 视频数(分区入口计数)。 */
    @Select("""
            SELECT category, count(*) AS cnt
            FROM videos
            WHERE status = 'PUBLISHED' AND category IS NOT NULL
            GROUP BY category
            """)
    List<Map<String, Object>> countPublishedByCategory();

    /** UP 主 PUBLISHED 视频数与总播放量(公开主页统计)。 */
    @Select("""
            SELECT count(*) AS video_count, COALESCE(sum(play_count), 0) AS total_play_count
            FROM videos
            WHERE owner_id = #{ownerId} AND status = 'PUBLISHED'
            """)
    Map<String, Object> selectPublishedStats(@Param("ownerId") Long ownerId);

    /** UP 偏好:用户播放历史中最常看的 UP 主 TOP3。 */
    @Select("""
            SELECT v.owner_id
            FROM play_histories ph
            JOIN videos v ON v.id = ph.video_id
            WHERE ph.user_id = #{userId}
            GROUP BY v.owner_id
            ORDER BY count(*) DESC
            LIMIT 3
            """)
    List<Long> selectTopOwnerIds(@Param("userId") Long userId);
}
