package com.trainereval.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class UserForm {
    @NotBlank(message = "用户名不能为空")
    private String username;
    private String password;
    @NotBlank(message = "姓名不能为空")
    private String realName;
    private String email;
    private String phone;
    private Integer gender;
    private Long deptId;
    private List<Long> roleIds;
}
