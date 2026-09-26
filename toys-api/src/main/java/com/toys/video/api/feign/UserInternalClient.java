package com.toys.video.api.feign;

import com.toys.video.common.api.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** user-service 内部接口:其他服务取用户展示信息(UP主名等)。 */
@FeignClient(name = "toys-user-service", contextId = "userInternalClient", path = "/internal/users",
        configuration = InternalFeignConfig.class,
        fallbackFactory = UserInternalClientFallbackFactory.class)
public interface UserInternalClient {

    @GetMapping("/batch")
    R<List<UserBrief>> batch(@RequestParam("ids") List<Long> ids);

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
}
