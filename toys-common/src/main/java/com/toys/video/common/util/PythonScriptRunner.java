package com.toys.video.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Python 脚本执行器:进程调用 + 超时控制 + stdout 上限保护 + stdout JSON 解析。机审与转码共用。 */
@Slf4j
@Component
public class PythonScriptRunner {

    private static final long TIMEOUT_MINUTES = 10;
    /** stdout 上限:累计超过即终止进程,防止异常脚本输出撑爆内存或塞满管道。 */
    private static final int MAX_STDOUT_BYTES = 10 * 1024 * 1024;
    private static final int READ_CHUNK_BYTES = 8192;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String pythonBin;

    public PythonScriptRunner(@Value("${toys.scripts.python-bin:python3}") String pythonBin) {
        this.pythonBin = pythonBin;
    }

    public JsonNode run(Path script, String... args) {
        String[] cmd = new String[args.length + 2];
        cmd[0] = pythonBin;
        cmd[1] = script.toString();
        System.arraycopy(args, 0, cmd, 2, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        try {
            Process process = pb.start();

            // stderr 由后台线程排空,避免管道写满导致子进程阻塞
            ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
            Thread stderrDrainer = new Thread(() -> drain(process.getErrorStream(), stderrBuffer));
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            // stdout 由后台线程限量读取:超限销毁进程并记录异常,主线程等待结束后统一抛出
            AtomicReference<String> stdoutRef = new AtomicReference<>("");
            AtomicReference<BizException> overflow = new AtomicReference<>();
            Thread stdoutReader = new Thread(() -> {
                try {
                    stdoutRef.set(readLimited(process.getInputStream()));
                } catch (BizException e) {
                    overflow.set(e);
                    process.destroyForcibly();
                } catch (IOException ignored) {
                    // 进程销毁导致的流关闭,由退出码分支兜底
                }
            });
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException(ErrorCode.INTERNAL_ERROR, "脚本执行超时: " + script.getFileName());
            }
            stdoutReader.join(TimeUnit.SECONDS.toMillis(10));
            if (overflow.get() != null) {
                throw overflow.get();
            }
            String stdout = stdoutRef.get();
            String stderr = stderrBuffer.toString(StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                log.error("script {} failed: {}", script, tail(stderr));
                throw new BizException(ErrorCode.INTERNAL_ERROR, "脚本执行失败: " + tail(stderr));
            }
            return objectMapper.readTree(stdout);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("script {} exec error", script, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "脚本执行异常");
        }
    }

    /** 循环限量读取:累计超过 {@link #MAX_STDOUT_BYTES} 时抛业务异常,由调用方销毁进程。 */
    private String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > MAX_STDOUT_BYTES) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "脚本输出异常");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void drain(InputStream in, ByteArrayOutputStream buffer) {
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        try {
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
        } catch (IOException ignored) {
            // 进程销毁导致的流关闭,忽略
        }
    }

    private String tail(String s) {
        if (s == null || s.isBlank()) {
            return "no stderr";
        }
        return s.length() > 400 ? s.substring(s.length() - 400) : s;
    }
}
