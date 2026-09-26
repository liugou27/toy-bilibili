package com.toys.video.video.service;

import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.video.discovery.Categorys;

import java.util.Arrays;
import java.util.List;

/**
 * 投稿/编辑的分区与标签入参规则(纯函数,便于单测)。
 * 标签按空白/逗号拆分,最多 5 个、每个不超过 16 字,以逗号串存储。
 */
public final class VideoMetaPolicy {

    private static final int MAX_TAGS = 5;
    private static final int MAX_TAG_LENGTH = 16;

    private VideoMetaPolicy() {
    }

    /** 分区:空视为未设置返回 null;非空必须为合法分区 key。 */
    public static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String key = category.trim();
        if (!Categorys.isValid(key)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "分区不合法");
        }
        return key;
    }

    /** 标签:按空白/逗号(含中文逗号)拆分,≤5 个、每个 ≤16 字,拼逗号串;无标签返回空串。 */
    public static String normalizeTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> tags = Arrays.stream(raw.split("[\\s,，]+"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
        if (tags.size() > MAX_TAGS) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "标签最多 " + MAX_TAGS + " 个");
        }
        for (String tag : tags) {
            if (tag.length() > MAX_TAG_LENGTH) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "标签不能超过 " + MAX_TAG_LENGTH + " 字");
            }
        }
        return String.join(",", tags);
    }

    /** 存储的逗号串拆回标签列表,detail 接口用。 */
    public static List<String> parseTags(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }
}
