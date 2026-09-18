package com.trainereval.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private String refreshToken;
    private Long expiresIn;
    private UserInfo userInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long userId;
        private String username;
        private String realName;
        private String avatar;
        private List<String> roles;
        private List<String> permissions;
        // 以下字段供「个人信息」页面展示。原来只有 6 个字段，
        // 个人资料页连自己的邮箱/手机号/部门都显示不出来。
        private String email;
        private String phone;
        private Integer gender;
        private Long deptId;
        private String deptName;
        private String lastLoginTime;
    }
}
