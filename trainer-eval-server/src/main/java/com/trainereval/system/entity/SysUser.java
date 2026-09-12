package com.trainereval.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.trainereval.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {
    private String username;
    @JsonIgnore
    private String password;
    private String realName;
    private String email;
    private String phone;
    private String avatar;
    private Integer gender;
    private Long deptId;
    private Integer status;
    private LocalDateTime lastLoginTime;

    @TableField(exist = false)
    private String deptName;

    @TableField(exist = false)
    private java.util.List<Long> roleIds;
}
