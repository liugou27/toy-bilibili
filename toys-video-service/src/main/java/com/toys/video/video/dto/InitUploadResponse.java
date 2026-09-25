package com.toys.video.video.dto;

import java.util.List;

/**
 * 分片上传初始化结果。
 * instant=true 表示秒传(服务端已有同内容文件,直接进入机审,无需上传);
 * 否则返回 uploadId / partSize / 已完成分片号(断点续传只补缺失分片)。
 */
public record InitUploadResponse(
        Long videoId,
        String uploadId,
        Long partSize,
        List<Integer> doneParts,
        boolean instant
) {
}
