package com.toys.video.api.feign;

import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** user-service 内部接口:其他服务取用户展示信息(UP主名等)。 */
@FeignClient(name = "toys-user-service", contextId = "userInternalClient", path = "/internal/users",
        configuration = InternalFeignConfig.class,
        fallbackFactory = UserInternalClientFallbackFactory.class)
public interface UserInternalClient {

    @GetMapping("/batch")
    R<List<UserBrief>> batch(@RequestParam("ids") List<Long> ids);

    /** 上报一条违规并返回最新处罚状态。 */
    @PostMapping("/violations")
    R<PunishStatus> reportViolation(@RequestBody ViolationReport report);

    /** 查询用户处罚状态,供下游做禁言/封禁拦截。 */
    @GetMapping("/{id}/punish")
    R<PunishStatus> punish(@PathVariable("id") Long id);

    record UserBrief(Long id, String username, String nickname, String avatar) {

        /** 不关心昵称时的构造,视为未设置昵称。 */
        public UserBrief(Long id, String username) {
            this(id, username, null, null);
        }

        /** 不关心头像时的构造。 */
        public UserBrief(Long id, String username, String nickname) {
            this(id, username, nickname, null);
        }
    }

    /** 处罚状态:muted=禁言,banned=封禁,violationCount=违规累计次数。 */
    record PunishStatus(boolean muted, boolean banned, long violationCount) {
    }

    /** 违规上报体:type 为 POLITIC|PORN|VULGAR|AD|OTHER,videoId 可空。 */
    record ViolationReport(Long userId, String type, String reason, Long videoId) {
    }
}
