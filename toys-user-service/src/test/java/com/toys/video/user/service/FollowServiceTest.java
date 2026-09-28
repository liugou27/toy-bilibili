package com.toys.video.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.user.entity.Follow;
import com.toys.video.user.entity.User;
import com.toys.video.user.mapper.FollowMapper;
import com.toys.video.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 关注体系单测:自关注拒绝、幂等、未登录语义。 */
class FollowServiceTest {

    private final FollowMapper followMapper = mock(FollowMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final FollowService service = new FollowService(followMapper, userMapper);

    @BeforeAll
    static void initLambdaCache() {
        // 纯单测环境无 MP 上下文,手工初始化 Lambda 元数据缓存
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Follow.class);
    }

    @Test
    void follow_self_rejected() {
        BizException ex = assertThrows(BizException.class, () -> service.follow(1L, 1L));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(followMapper, never()).insert(ArgumentMatchers.any(Follow.class));
        verifyNoInteractions(userMapper);
    }

    @Test
    void follow_targetMissing_notFound() {
        when(userMapper.selectById(2L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.follow(1L, 2L));
        verify(followMapper, never()).insert(ArgumentMatchers.any(Follow.class));
    }

    @Test
    void follow_alreadyFollowed_idempotent() {
        when(userMapper.selectById(2L)).thenReturn(new User());
        when(followMapper.selectCount(ArgumentMatchers.<Wrapper<Follow>>any())).thenReturn(1L);

        service.follow(1L, 2L);

        verify(followMapper, never()).insert(ArgumentMatchers.any(Follow.class));
    }

    @Test
    void follow_newRelationship_inserted() {
        when(userMapper.selectById(2L)).thenReturn(new User());
        when(followMapper.selectCount(ArgumentMatchers.<Wrapper<Follow>>any())).thenReturn(0L);

        service.follow(1L, 2L);

        verify(followMapper).insert(ArgumentMatchers.any(Follow.class));
    }

    @Test
    void unfollow_notFollowing_idempotent() {
        service.unfollow(1L, 2L);

        verify(followMapper).delete(ArgumentMatchers.<Wrapper<Follow>>any());
    }

    @Test
    void followed_notLogin_false() {
        assertFalse(service.followed(null, 2L));
        verifyNoInteractions(followMapper);
    }

    @Test
    void followed_following_returnsTrue() {
        when(followMapper.selectCount(ArgumentMatchers.<Wrapper<Follow>>any())).thenReturn(1L);

        assertTrue(service.followed(1L, 2L));
    }
}
