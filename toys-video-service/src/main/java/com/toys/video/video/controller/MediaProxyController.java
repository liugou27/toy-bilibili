package com.toys.video.video.controller;

import com.toys.video.video.config.MinioConfig;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 媒体流式代理:hls 桶私有化后,/media/** 由本服务带凭证从 MinIO 读取并流出。
 * 对象路径白名单(仅 hls/{videoId}/ 下的 master/分档播放表/分片/封面),
 * 阻断任意 key 枚举;支持单段 Range(进度条拖动)。
 */
@Slf4j
@RestController
@RequestMapping("/media")
@RequiredArgsConstructor
public class MediaProxyController {

    /** 白名单:hls/{数字id}/ 下的四类合法产物文件名。 */
    private static final Pattern ALLOWED = Pattern.compile(
            "^(\\d+)/(master\\.m3u8|\\d+p\\.m3u8|\\d+p_\\d+\\.ts|poster\\.jpg)$");

    private final MinioClient minioClient;

    @GetMapping("/hls/{videoId}/{file:.+}")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> stream(
            @PathVariable String videoId,
            @PathVariable String file,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        String objectKey = videoId + "/" + file;
        if (!ALLOWED.matcher(objectKey).matches()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        long objectSize = statSize(objectKey);
        if (objectSize <= 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        long[] range = parseRange(rangeHeader, objectSize);

        GetObjectArgs.Builder args = GetObjectArgs.builder()
                .bucket(MinioConfig.BUCKET_HLS)
                .object(objectKey);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType(file));
        if (range != null) {
            args.offset(range[0]);
            args.length(range[1] - range[0] + 1);
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range[0] + "-" + range[1] + "/" + objectSize);
            headers.setContentLength(range[1] - range[0] + 1);
        } else {
            headers.setContentLength(objectSize);
        }
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setCacheControl("public, max-age=300");

        try {
            InputStream in = minioClient.getObject(args.build());
            StreamingResponseBody body = out -> {
                try (in) {
                    in.transferTo(out);
                }
            };
            return ResponseEntity.status(range != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                    .headers(headers).body(body);
        } catch (Exception e) {
            log.warn("media proxy miss: {} ({})", objectKey, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    private long statSize(String objectKey) {
        try {
            return minioClient.statObject(io.minio.StatObjectArgs.builder()
                    .bucket(MinioConfig.BUCKET_HLS)
                    .object(objectKey)
                    .build()).size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 解析单段 Range: bytes=start-end;非法返回 null(整对象返回)。 */
    private long[] parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) {
            return null;
        }
        String spec = header.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long start = spec.substring(0, dash).isBlank() ? 0 : Long.parseLong(spec.substring(0, dash));
            long end = spec.substring(dash + 1).isBlank() ? size - 1 : Long.parseLong(spec.substring(dash + 1));
            if (start < 0 || end >= size || start > end) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private MediaType mediaType(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".m3u8")) {
            return MediaType.parseMediaType("application/vnd.apple.mpegurl");
        }
        if (lower.endsWith(".ts")) {
            return MediaType.parseMediaType("video/mp2t");
        }
        if (lower.endsWith(".jpg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
