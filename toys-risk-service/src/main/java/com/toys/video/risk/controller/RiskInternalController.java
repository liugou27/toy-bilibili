package com.toys.video.risk.controller;

import com.toys.video.common.api.R;
import com.toys.video.common.constant.Headers;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.risk.dto.RiskRequest;
import com.toys.video.risk.dto.RiskResult;

import com.toys.video.risk.engine.RiskScoringEngine;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部接口:风控评级(网关不路由 /internal/**,仅服务间 Feign 直连)。 */
@Slf4j
@RestController
@RequestMapping("/internal/risk")
@RequiredArgsConstructor
public class RiskInternalController {

    private final RiskScoringEngine scoringEngine;

    @PostMapping("/evaluate")
    public R<RiskResult> evaluate(@RequestBody RiskRequest request, HttpServletRequest httpRequest) {
        if (!"1".equals(httpRequest.getHeader(Headers.INTERNAL_CALL))) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        RiskResult result = scoringEngine.evaluate(request);
        log.info("risk evaluated: score={} level={} items={}",
                result.score(), result.level(), result.riskItems().size());
        return R.ok(result);
    }
}
