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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
