package com.trainereval.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.system.entity.SysRole;
import com.trainereval.system.entity.SysUser;
import com.trainereval.system.mapper.SysMenuMapper;
import com.trainereval.system.mapper.SysRoleMapper;
import com.trainereval.system.mapper.SysUserMapper;
import com.trainereval.system.mapper.SysUserRoleMapper;
import com.trainereval.system.entity.SysUserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private SysRoleMapper roleMapper;
    @Autowired
    private SysMenuMapper menuMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        if (user.getStatus() == 0) {
            throw new UsernameNotFoundException("用户已被禁用");
        }

        List<Long> roleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, user.getId()))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());

        List<String> roles = List.of();
        if (!roleIds.isEmpty()) {
            roles = roleMapper.selectBatchIds(roleIds).stream()
                    .map(SysRole::getRoleCode).collect(Collectors.toList());
        }

        List<String> perms = menuMapper.selectPermsByUserId(user.getId());

        return new LoginUser(user, perms, roles);
    }

    public String getUsernameById(Long userId) {
        SysUser user = userMapper.selectById(userId);
        return user != null ? user.getUsername() : null;
    }
}
