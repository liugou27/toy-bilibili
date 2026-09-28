package com.toys.video.moderation.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.moderation.entity.ViolationSample;
import com.toys.video.moderation.mapper.ViolationSampleMapper;
import com.toys.video.moderation.service.ViolationSampleService;
import com.toys.video.moderation.util.PHashUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Set;

/** 违规黑样本运营后台:分页/新增(phash 16 位 hex)/删除,写操作后立即热更新样本库。网关已校验 ADMIN 角色,这里二次校验。 */
@RestController
@RequestMapping("/api/admin/violation-samples")
@RequiredArgsConstructor
public class ViolationSampleAdminController {

    private static final Set<String> VALID_TYPES = Set.of("POLITIC", "PORN", "VULGAR", "AD", "OTHER");

    private final ViolationSampleMapper sampleMapper;
    private final ViolationSampleService violationSampleService;

    /** 分页:按 created_at 倒序,支持 type 精确过滤。 */
    @GetMapping
    public R<PageResult<ViolationSample>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String type) {
        requireAdmin();
        boolean hasType = type != null && !type.isBlank();
        IPage<ViolationSample> p = sampleMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<ViolationSample>()
                        .eq(hasType, ViolationSample::getType, hasType ? type.trim() : null)
                        .orderByDesc(ViolationSample::getCreatedAt));
        return R.ok(new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    @PostMapping
    public R<Void> create(@RequestBody SampleRequest req) {
        requireAdmin();
        Long phash = requirePhash(req.getPhash());
        String type = requireType(req.getType());
        String note = normalizeNote(req.getNote());
        ViolationSample row = new ViolationSample();
        row.setPhash(phash);
        row.setType(type);
        row.setNote(note);
        row.setCreatedAt(LocalDateTime.now());
        sampleMapper.insert(row);
        violationSampleService.reload();
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        requireAdmin();
        if (sampleMapper.deleteById(id) == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "黑样本不存在");
        }
        violationSampleService.reload();
        return R.ok();
    }

    /** phash 必须是 16 位 hex(与 auto_screen.py 输出一致),统一转小写后落库为 long。 */
    private Long requirePhash(String phash) {
        if (phash == null || phash.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "phash 不能为空");
        }
        Long value = PHashUtil.fromHex(phash.trim().toLowerCase());
        if (value == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "phash 必须是 16 位十六进制字符串");
        }
        return value;
    }

    private String requireType(String type) {
        if (type == null || !VALID_TYPES.contains(type.trim())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "类型仅支持 POLITIC/PORN/VULGAR/AD/OTHER");
        }
        return type.trim();
    }

    private String normalizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > 200) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "备注不能超过200字");
        }
        return trimmed;
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(UserContext.userRole())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    @Data
    public static class SampleRequest {
        private String phash;
        private String type;
        private String note;
    }
}
