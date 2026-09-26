package com.toys.video.video.text;

import com.toys.video.api.feign.SensitiveWordsInternalClient;
import com.toys.video.common.api.R;
import com.toys.video.common.text.SensitiveWordFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/** 敏感词热更新持有者:启动加载、版本变化整体替换、版本不变不替换、Feign 失败保留本地词库。 */
@ExtendWith(MockitoExtension.class)
class SensitiveWordHolderTest {

    @Mock
    private SensitiveWordsInternalClient client;

    @Test
    void initialLoad_populatesFilterFromSnapshot() {
        // 覆盖先前 thenThrow 桩:doReturn 不真实调用 mock,避免触发上一个异常桩
        doReturn(R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(
                7L, Map.of("赌博", SensitiveWordFilter.LEVEL_REJECT)))).when(client).snapshot(0L);
        SensitiveWordHolder holder = new SensitiveWordHolder(client);
        holder.initialLoad();
        assertEquals(SensitiveWordFilter.LEVEL_REJECT, holder.current().words().get("赌博"));
    }

    @Test
    void refresh_replacesFilterWhenVersionChanges() {
        // 覆盖先前 thenThrow 桩:doReturn 不真实调用 mock,避免触发上一个异常桩
        doReturn(R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(
                7L, Map.of("赌博", SensitiveWordFilter.LEVEL_REJECT)))).when(client).snapshot(0L);
        SensitiveWordHolder holder = new SensitiveWordHolder(client);
        holder.initialLoad();

        when(client.snapshot(7L)).thenReturn(R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(
                9L, Map.of("博彩", SensitiveWordFilter.LEVEL_REJECT))));
        holder.refresh();

        assertEquals(SensitiveWordFilter.LEVEL_REJECT, holder.current().words().get("博彩"));
        assertNull(holder.current().words().get("赌博"));
    }

    @Test
    void refresh_keepsFilterWhenVersionUnchanged() {
        // 覆盖先前 thenThrow 桩:doReturn 不真实调用 mock,避免触发上一个异常桩
        doReturn(R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(
                7L, Map.of("赌博", SensitiveWordFilter.LEVEL_REJECT)))).when(client).snapshot(0L);
        SensitiveWordHolder holder = new SensitiveWordHolder(client);
        holder.initialLoad();

        when(client.snapshot(7L)).thenReturn(R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(7L, null)));
        holder.refresh();

        assertEquals(SensitiveWordFilter.LEVEL_REJECT, holder.current().words().get("赌博"));
    }

    @Test
    void refresh_feignFailureKeepsLocalWordBook() {
        when(client.snapshot(0L)).thenThrow(new RuntimeException("connection refused"));
        SensitiveWordHolder holder = new SensitiveWordHolder(client);
        assertDoesNotThrow(holder::initialLoad);
        assertTrue(holder.current().words().isEmpty());

        // 覆盖先前 thenThrow 桩:doReturn 不真实调用 mock,避免触发上一个异常桩
        doReturn(R.ok(new SensitiveWordsInternalClient.SensitiveSnapshot(
                7L, Map.of("赌博", SensitiveWordFilter.LEVEL_REJECT)))).when(client).snapshot(0L);
        holder.refresh();
        assertEquals(SensitiveWordFilter.LEVEL_REJECT, holder.current().words().get("赌博"));
    }
}
