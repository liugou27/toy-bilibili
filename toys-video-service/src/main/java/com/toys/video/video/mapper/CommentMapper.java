package com.toys.video.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.toys.video.video.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
