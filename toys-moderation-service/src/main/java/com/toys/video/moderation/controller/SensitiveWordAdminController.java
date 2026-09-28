package com.toys.video.moderation.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.common.text.SensitiveWordFilter;
import com.toys.video.moderation.entity.SensitiveWord;
import com.toys.video.moderation.mapper.SensitiveWordMapper;
import com.toys.video.moderation.service.SensitiveWordService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 敏感词库运营后台:分页/新增/删除/启停/批量导入,写操作后立即热更新词库。网关已校验 ADMIN 角色,这里二次校验。 */
@RestController
@RequestMapping("/api/admin/sensitive-words")
@RequiredArgsConstructor
public class SensitiveWordAdminController {

    private final SensitiveWordMapper wordMapper;
    private final SensitiveWordService sensitiveWordService;

    /** 分页:按 updated_at 倒序,支持 word 模糊。 */
    @GetMapping
    public R<PageResult<SensitiveWord>> page(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String word) {
        requireAdmin();
        // like 的 val 参数无论条件真假都会求值,先归一避免 NPE
        boolean hasKeyword = word != null && !word.isBlank();
        String keyword = hasKeyword ? word.trim() : null;
        IPage<SensitiveWord> p = wordMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<SensitiveWord>()
                        .like(hasKeyword, SensitiveWord::getWord, keyword)
                        .orderByDesc(SensitiveWord::getUpdatedAt));
        return R.ok(new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    @PostMapping
    public R<Void> create(@RequestBody WordRequest req) {
        requireAdmin();
        String word = requireWord(req.getWord());
        String level = requireLevel(req.getLevel());
        String category = requireCategory(req.getCategory());
        Long count = wordMapper.selectCount(new LambdaQueryWrapper<SensitiveWord>()
                .eq(SensitiveWord::getWord, word));
        if (count != null && count > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "敏感词已存在: " + word);
        }
        LocalDateTime now = LocalDateTime.now();
        SensitiveWord row = new SensitiveWord();
        row.setWord(word);
        row.setLevel(level);
        row.setCategory(category);
        row.setStatus("ENABLED");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            wordMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "敏感词已存在: " + word);
        }
        sensitiveWordService.reload();
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        requireAdmin();
        if (wordMapper.deleteById(id) == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "敏感词不存在");
        }
        sensitiveWordService.reload();
        return R.ok();
    }

    @PatchMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        requireAdmin();
        String status = requireStatus(req.getStatus());
        int rows = wordMapper.update(null, new LambdaUpdateWrapper<SensitiveWord>()
                .eq(SensitiveWord::getId, id)
                .set(SensitiveWord::getStatus, status)
                .set(SensitiveWord::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "敏感词不存在");
        }
        sensitiveWordService.reload();
        return R.ok();
    }

    /** 批量导入:逐条 upsert,已存在的词更新级别并重新启用。 */
    @PostMapping("/import")
    public R<Integer> importWords(@RequestBody ImportRequest req) {
        requireAdmin();
        if (req.getWords() == null || req.getWords().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "导入列表为空");
        }
        if (req.getWords().size() > 500) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "单次导入最多 500 个词");
        }
        String level = requireLevel(req.getLevel());
        if (req.getWords() == null || req.getWords().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "词列表不能为空");
        }
        int count = 0;
        for (String raw : req.getWords()) {
            String word = requireWord(raw);
            LocalDateTime now = LocalDateTime.now();
            SensitiveWord exists = wordMapper.selectOne(new LambdaQueryWrapper<SensitiveWord>()
                    .eq(SensitiveWord::getWord, word));
            if (exists == null) {
                SensitiveWord row = new SensitiveWord();
                row.setWord(word);
                row.setLevel(level);
                row.setStatus("ENABLED");
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                wordMapper.insert(row);
            } else {
                wordMapper.update(null, new LambdaUpdateWrapper<SensitiveWord>()
                        .eq(SensitiveWord::getId, exists.getId())
                        .set(SensitiveWord::getLevel, level)
                        .set(SensitiveWord::getStatus, "ENABLED")
                        .set(SensitiveWord::getUpdatedAt, now));
            }
            count++;
        }
        sensitiveWordService.reload();
        return R.ok(count);
    }

    private String requireWord(String word) {
        if (word == null || word.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "敏感词不能为空");
        }
        String trimmed = word.trim();
        if (trimmed.length() > 64) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "敏感词不能超过64字");
        }
        return trimmed;
    }

    private String requireLevel(String level) {
        if (!SensitiveWordFilter.LEVEL_REJECT.equals(level)
                && !SensitiveWordFilter.LEVEL_REVIEW.equals(level)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "级别仅支持 REJECT/REVIEW");
        }
        return level;
    }

    private String requireStatus(String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "状态仅支持 ENABLED/DISABLED");
        }
        return status;
    }

    private String requireCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String trimmed = category.trim();
        if (trimmed.length() > 32) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "分类不能超过32字");
        }
        return trimmed;
    }

    private void requireAdmin() {
        if (!"ADMIN".equals(UserContext.userRole())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    @Data
    public static class WordRequest {
        private String word;
        private String level;
        private String category;
    }

    @Data
    public static class StatusRequest {
        private String status;
    }

    @Data
    public static class ImportRequest {
        private List<String> words;
        private String level;
    }
}
