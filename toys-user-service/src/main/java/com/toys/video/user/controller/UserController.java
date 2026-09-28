package com.toys.video.user.controller;

import com.toys.video.common.api.PageResult;
import com.toys.video.common.api.R;
import com.toys.video.common.context.UserContext;
import com.toys.video.common.exception.BizException;
import com.toys.video.common.exception.ErrorCode;
import com.toys.video.user.dto.FollowItem;
import com.toys.video.user.dto.FollowStats;
import com.toys.video.user.dto.UpdateProfileRequest;
import com.toys.video.user.dto.UserInfo;
import com.toys.video.user.service.AuthService;
import com.toys.video.user.service.FollowService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final FollowService followService;

    @GetMapping("/me")
    public R<UserInfo> me() {
        return R.ok(authService.me(requireLogin()));
    }

    /** 编辑资料:仅登录本人;字段不传则保持原值。 */
    @PatchMapping("/me")
    public R<UserInfo> updateMe(@Valid @RequestBody UpdateProfileRequest req) {
        return R.ok(authService.updateProfile(requireLogin(), req.nickname(), req.avatar()));
    }

    /** 关注 TA:禁止自关注,重复关注幂等。 */
    @PostMapping("/{id}/follow")
    public R<Void> follow(@PathVariable Long id) {
        followService.follow(requireLogin(), id);
        return R.ok();
    }

    /** 取关 TA:未关注也幂等成功。 */
    @DeleteMapping("/{id}/follow")
    public R<Void> unfollow(@PathVariable Long id) {
        followService.unfollow(requireLogin(), id);
        return R.ok();
    }

    /** TA 关注的人。 */
    @GetMapping("/{id}/follows")
    public R<PageResult<FollowItem>> follows(@PathVariable Long id,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return R.ok(followService.following(id, page, size));
    }

    /** TA 的粉丝。 */
    @GetMapping("/{id}/fans")
    public R<PageResult<FollowItem>> fans(@PathVariable Long id,
                                          @RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        return R.ok(followService.fans(id, page, size));
    }

    /** 当前登录人是否关注了 TA,未登录返回 false。 */
    @GetMapping("/{id}/followed")
    public R<Boolean> followed(@PathVariable Long id) {
        return R.ok(followService.followed(UserContext.userId(), id));
    }

    /** 关注统计。 */
    @GetMapping("/{id}/stats")
    public R<FollowStats> stats(@PathVariable Long id) {
        return R.ok(followService.stats(id));
    }

    private Long requireLogin() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
