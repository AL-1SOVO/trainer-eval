package com.trainereval.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.trainereval.common.result.PageResult;
import com.trainereval.system.dto.UserForm;
import com.trainereval.system.dto.UserQuery;
import com.trainereval.system.entity.SysUser;
import java.util.List;

public interface SysUserService extends IService<SysUser> {
    PageResult<SysUser> pageUsers(UserQuery query);
    void createUser(UserForm form);
    void registerUser(UserForm form);
    void updateUser(Long id, UserForm form);
    void assignRoles(Long userId, List<Long> roleIds);
    void resetPassword(Long userId, String newPassword);
    void updateStatus(Long userId, Integer status);
}
