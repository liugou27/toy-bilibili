package com.toys.video.moderation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动清扫进程崩溃残留的 autoscreen-* 临时目录(超过 1 小时,防误删并行任务)。 */
@Slf4j
@Component
public class TempDirSweeper implements ApplicationRunner {

    private static final long ORPHAN_DIR_AGE_MS = 3600_000L;

    @Override
    public void run(ApplicationArguments args) {
        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        long cutoff = System.currentTimeMillis() - ORPHAN_DIR_AGE_MS;
        try (var stream = java.nio.file.Files.list(tmp)) {
            stream.filter(f -> f.getFileName().toString().startsWith("autoscreen-"))
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
