package com.trainereval.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.util.RedisUtils;
import com.trainereval.system.dto.UserForm;
import com.trainereval.system.dto.UserQuery;
import com.trainereval.system.entity.SysRole;
import com.trainereval.system.entity.SysUser;
import com.trainereval.system.entity.SysUserRole;
import com.trainereval.system.mapper.SysRoleMapper;
import com.trainereval.system.mapper.SysUserMapper;
import com.trainereval.system.mapper.SysUserRoleMapper;
import com.trainereval.system.service.SysUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private SysRoleMapper roleMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RedisUtils redisUtils;

    @Override
    public PageResult<SysUser> pageUsers(UserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getUsername())) {
            wrapper.like(SysUser::getUsername, query.getUsername());
        }
        if (StringUtils.hasText(query.getRealName())) {
            wrapper.like(SysUser::getRealName, query.getRealName());
        }
        if (query.getDeptId() != null) {
            wrapper.eq(SysUser::getDeptId, query.getDeptId());
        }
        if (query.getStatus() != null) {
            wrapper.eq(SysUser::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(SysUser::getCreateTime);

        Page<SysUser> page = new Page<>(query.getPage(), query.getSize());
        Page<SysUser> result = baseMapper.selectPage(page, wrapper);

        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    @Transactional
    public void createUser(UserForm form) {
        SysUser user = buildAndSaveUser(form, "123456");
        assignRoles(user.getId(), form.getRoleIds());
    }

    @Override
    @Transactional
    public void registerUser(UserForm form) {
        SysUser user = buildAndSaveUser(form, null);
        SysRole employeeRole = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, "ROLE_EMPLOYEE")
                .last("LIMIT 1"));
        if (employeeRole == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        assignRoles(user.getId(), List.of(employeeRole.getId()));
    }

    @Override
    @Transactional
    public void updateUser(Long id, UserForm form) {
        SysUser user = baseMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        user.setRealName(form.getRealName());
        user.setEmail(form.getEmail());
        user.setPhone(form.getPhone());
        user.setGender(form.getGender());
        user.setDeptId(form.getDeptId());
        baseMapper.updateById(user);

        if (form.getRoleIds() != null) {
            LambdaQueryWrapper<SysUserRole> urWrapper = new LambdaQueryWrapper<>();
            urWrapper.eq(SysUserRole::getUserId, id);
            userRoleMapper.delete(urWrapper);
            for (Long roleId : form.getRoleIds()) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(id);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        LambdaQueryWrapper<SysUserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUserRole::getUserId, userId);
        userRoleMapper.delete(wrapper);

        if (roleIds != null) {
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
    }

    @Override
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = baseMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        user.setPassword(passwordEncoder.encode(
                StringUtils.hasText(newPassword) ? newPassword : "123456"));
        baseMapper.updateById(user);

        // 重置密码必须同时作废该用户**已有的会话**（token:gen 自增，与登出/自助改密同一套机制）。
        // 否则"重置密码"在最需要它的场景下是无效的：账号被盗用时管理员重置了密码，
        // 但攻击者手里的 access/refresh token 仍然可用 —— 重置就没起到把人踢下线的作用。
        redisUtils.increment("token:gen:" + userId);
    }

    @Override
    public void updateStatus(Long userId, Integer status) {
        SysUser user = baseMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        user.setStatus(status);
        baseMapper.updateById(user);
    }

    private SysUser buildAndSaveUser(UserForm form, String defaultPassword) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, form.getUsername());
        if (baseMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        String password = StringUtils.hasText(form.getPassword()) ? form.getPassword() : defaultPassword;
        if (!StringUtils.hasText(password)) {
            throw new BusinessException("注册密码不能为空");
        }

        SysUser user = new SysUser();
        user.setUsername(form.getUsername());
        user.setPassword(passwordEncoder.encode(password));
        user.setRealName(form.getRealName());
        user.setEmail(form.getEmail());
        user.setPhone(form.getPhone());
        user.setGender(form.getGender());
        user.setDeptId(form.getDeptId());
        user.setStatus(1);
        baseMapper.insert(user);
        return user;
    }
}
