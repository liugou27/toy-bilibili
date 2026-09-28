package com.toys.video.user.service;

import com.toys.video.user.dto.PunishStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 处罚判定纯函数单测:阈值、禁言时效、手动封禁。 */
class PunishServiceTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);

    @Test
    void evaluate_belowMuteThreshold_noPunish() {
        PunishStatus s = PunishService.evaluate(2, now.minusHours(1), null, now);

        assertFalse(s.muted());
        assertFalse(s.banned());
        assertEquals(2, s.violationCount());
    }

    @Test
    void evaluate_reachMuteThreshold_muted() {
        PunishStatus s = PunishService.evaluate(3, now.minusHours(1), null, now);

        assertTrue(s.muted());
        assertFalse(s.banned());
    }

    @Test
    void evaluate_fourViolations_stillMutedNotBanned() {
        PunishStatus s = PunishService.evaluate(4, now.minusDays(1), null, now);

        assertTrue(s.muted());
        assertFalse(s.banned());
    }

    @Test
    void evaluate_reachBanThreshold_bannedForever() {
        // 最近一次违规早已超过 7 天,封禁仍是永久,不吃时间窗
        PunishStatus s = PunishService.evaluate(5, now.minusDays(30), null, now);

        assertTrue(s.banned());
        assertFalse(s.muted());
    }

    @Test
    void evaluate_muteWithinSevenDays_muted() {
        // 差 1 秒满 7 天:仍在禁言期
        PunishStatus s = PunishService.evaluate(3, now.minusDays(7).plusSeconds(1), null, now);

        assertTrue(s.muted());
    }

    @Test
    void evaluate_muteExpiresAfterSevenDays_released() {
        // 满 7 天整点即解除(闭区间右端)
        PunishStatus s = PunishService.evaluate(3, now.minusDays(7), null, now);

        assertFalse(s.muted());
        assertFalse(s.banned());
    }

    @Test
    void evaluate_manualBanNotExpired_banned() {
        PunishStatus s = PunishService.evaluate(0, null, now.plusDays(1), now);

        assertTrue(s.banned());
        assertFalse(s.muted());
    }

    @Test
    void evaluate_manualBanExpired_released() {
        PunishStatus s = PunishService.evaluate(0, null, now.minusSeconds(1), now);

        assertFalse(s.banned());
        assertFalse(s.muted());
    }

    @Test
    void evaluate_banTakesPrecedenceOverMute() {
        // 同时满足禁言与手动封禁:只报 banned
        PunishStatus s = PunishService.evaluate(4, now.minusHours(1), now.plusDays(1), now);

        assertTrue(s.banned());
        assertFalse(s.muted());
    }
}
