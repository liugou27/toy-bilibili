package com.toys.video.moderation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.toys.video.moderation.entity.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {

    /** 词库版本:最大 updated_at 的 epoch millis;空表返回 0。 */
    @Select("SELECT COALESCE(MAX(EXTRACT(EPOCH FROM updated_at)) * 1000, 0)::bigint FROM sensitive_words")
    Long selectMaxVersion();
}
