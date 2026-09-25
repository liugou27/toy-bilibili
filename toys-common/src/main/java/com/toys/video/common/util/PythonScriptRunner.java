package com.toys.video.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Python 脚本执行器:进程调用 + 超时控制 + stdout JSON 解析。机审与转码共用。 */
@Slf4j
@Component
public class PythonScriptRunner {

    private static final long TIMEOUT_MINUTES = 10;

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
            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException(ErrorCode.INTERNAL_ERROR, "脚本执行超时: " + script.getFileName());
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
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

    private String tail(String s) {
        if (s == null || s.isBlank()) {
            return "no stderr";
        }
        return s.length() > 400 ? s.substring(s.length() - 400) : s;
    }
}
