package com.toys.video.user.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.toys.video.common.util.BloomFilter;
import com.toys.video.user.entity.User;
import com.toys.video.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsernameBloomServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final UsernameBloomService service = new UsernameBloomService(userMapper);

    @org.junit.jupiter.api.BeforeAll
    static void initLambdaCache() {
        // 纯单测环境无 MP 上下文,手工初始化 Lambda 元数据缓存
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""), com.toys.video.user.entity.User.class);
    }

    @Test
    void init_buildsFilterFromFullScan() {
        mockScan("alice", "bob");
        ReflectionTestUtils.setField(service, "capacity", 1000L);

        service.init();

        assertTrue(service.mightContain("alice"));
        assertTrue(service.mightContain("bob"));
        // 容量 1000 只装入 2 个用户名,实测误判概率远低于标称值,负样本断言稳定
        assertFalse(service.mightContain("carol-never-registered"));
    }

    @Test
    void add_registersNewUsername() {
        mockScan();
        ReflectionTestUtils.setField(service, "capacity", 1000L);
        service.init();

        service.add("newbie");

        assertTrue(service.mightContain("newbie"));
    }

    @Test
    void rebuildWhenSaturated_doublesCapacityAndSwapsFilter() {
        mockScan("alice", "bob");
        ReflectionTestUtils.setField(service, "capacity", 8L);
        service.init();
        BloomFilter old = service.current();
        assertEquals(8, old.expectedInsertions());

        // 灌入元素直到饱和度越过重建阈值
        for (int i = 0; service.current().saturation() <= 0.5 && i < 100; i++) {
            service.add("extra-" + i);
        }
        assertTrue(service.current().saturation() > 0.5);

        service.rebuildWhenSaturated();

        BloomFilter rebuilt = service.current();
        assertNotSame(old, rebuilt);
        assertEquals(16, rebuilt.expectedInsertions());
        // 旧数据迁移:原用户名在新过滤器中仍然命中
        assertTrue(rebuilt.mightContain("alice"));
        assertTrue(rebuilt.mightContain("bob"));
    }

    @Test
    void rebuildWhenSaturated_keepsOldFilterWhenScanFails() {
        mockScan("alice");
        ReflectionTestUtils.setField(service, "capacity", 8L);
        service.init();
        BloomFilter old = service.current();
        for (int i = 0; service.current().saturation() <= 0.5 && i < 100; i++) {
            service.add("extra-" + i);
        }
        when(userMapper.selectList(ArgumentMatchers.<Wrapper<User>>any()))
                .thenThrow(new RuntimeException("db down"));

        service.rebuildWhenSaturated();

        assertSame(old, service.current());
    }

    @Test
    void rebuildWhenSaturated_skipsWhenSaturationBelowThreshold() {
        mockScan("alice", "bob");
        ReflectionTestUtils.setField(service, "capacity", 100_000L);
        service.init();
        BloomFilter before = service.current();

        service.rebuildWhenSaturated();

        assertSame(before, service.current());
        // init 消耗两批(数据批 + 空批),未重建则不再触发扫描
        verify(userMapper, times(2)).selectList(ArgumentMatchers.<Wrapper<User>>any());
    }

    @Test
    void worksAsNoOpWhenFilterMissing() {
        assertFalse(service.mightContain("anything"));
        assertNull(service.current());
        // 过滤器缺失时不抛异常,注册链路退化为 DB 查重
        service.add("ghost");
        assertNull(service.current());
    }

    /** 模拟分批扫描:每次扫描第一页返回数据、第二页返回空,如此往复(支撑启动构建与多次重建)。 */
    @SuppressWarnings("unchecked")
    private void mockScan(String... usernames) {
        List<User> batch = new ArrayList<>();
        for (int i = 0; i < usernames.length; i++) {
            User user = new User();
            user.setId((long) i + 1);
            user.setUsername(usernames[i]);
            batch.add(user);
        }
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(userMapper.selectList(ArgumentMatchers.<Wrapper<User>>any()))
                .thenAnswer(inv -> calls.incrementAndGet() % 2 == 1 ? batch : List.of());
    }
}
