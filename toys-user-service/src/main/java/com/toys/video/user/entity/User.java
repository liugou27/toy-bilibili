package com.toys.video.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    private String passwordHash;

    /** USER | ADMIN */
    private String role;

    /** 展示昵称,未设置时展示回退 username。 */
    private String nickname;

    /** 头像地址,未设置时前端显示首字圆点。 */
    private String avatar;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
