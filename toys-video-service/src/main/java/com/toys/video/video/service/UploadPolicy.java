package com.toys.video.video.service;

/**
 * 分片上传策略:分片大小与分片数约束。
 */
public final class UploadPolicy {

    /** composeObject 要求源对象 ≥5MiB(最后一片除外) */
    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    /** S3 multipart 分片数上限 */
    public static final int MAX_PARTS = 10_000;

    private UploadPolicy() {
    }

    /** 按文件大小计算分片大小:在 ≥5MiB 前提下保证分片数不超过 MAX_PARTS。 */
    public static long computePartSize(long fileSize) {
        long partSize = MIN_PART_SIZE;
        if (fileSize / MAX_PARTS > partSize) {
            partSize = (fileSize + MAX_PARTS - 1) / MAX_PARTS;
        }
        return partSize;
    }
}
