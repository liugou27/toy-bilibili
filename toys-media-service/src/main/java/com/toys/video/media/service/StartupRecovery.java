package com.toys.video.media.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动清扫:回收本机残留的转码临时目录(进程崩溃遗留,超过 1 小时才清,防误删并行任务)。 */
@Slf4j
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final long ORPHAN_DIR_AGE_MS = 3600_000L;

    @Override
    public void run(ApplicationArguments args) {
        sweepOrphanTempDirs();
    }

    private void sweepOrphanTempDirs() {
        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        long cutoff = System.currentTimeMillis() - ORPHAN_DIR_AGE_MS;
        try (var stream = java.nio.file.Files.list(tmp)) {
            stream.filter(f -> f.getFileName().toString().startsWith("transcode-"))
                    .filter(f -> {
                        try {
                            return java.nio.file.Files.getLastModifiedTime(f).toMillis() < cutoff;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .forEach(dir -> {
                        try (var walk = java.nio.file.Files.walk(dir)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                    .forEach(p -> p.toFile().delete());
                            log.info("swept orphan temp dir {}", dir);
                        } catch (Exception e) {
                            log.warn("sweep {} failed: {}", dir, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("orphan temp sweep failed: {}", e.getMessage());
        }
    }
}
