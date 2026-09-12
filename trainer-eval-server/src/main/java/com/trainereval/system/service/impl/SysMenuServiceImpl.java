package com.trainereval.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.trainereval.system.entity.SysMenu;
import com.trainereval.system.entity.SysRoleMenu;
import com.trainereval.system.entity.SysUserRole;
import com.trainereval.system.mapper.SysMenuMapper;
import com.trainereval.system.mapper.SysRoleMenuMapper;
import com.trainereval.system.mapper.SysUserRoleMapper;
import com.trainereval.system.service.SysMenuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

    @Autowired
    private SysRoleMenuMapper roleMenuMapper;
    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Override
    public List<SysMenu> getMenuTree() {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getStatus, 1).orderByAsc(SysMenu::getSortOrder);
        List<SysMenu> all = baseMapper.selectList(wrapper);
        return buildTree(all, 0L);
    }

    @Override
    public List<SysMenu> getRoleMenuTree(Long roleId) {
        List<SysMenu> allMenus = getMenuTree();
        LambdaQueryWrapper<SysRoleMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRoleMenu::getRoleId, roleId);
        Set<Long> checkedIds = roleMenuMapper.selectList(wrapper).stream()
                .map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
        markChecked(allMenus, checkedIds);
        return allMenus;
    }

    private void markChecked(List<SysMenu> menus, Set<Long> checkedIds) {
        for (SysMenu menu : menus) {
            if (checkedIds.contains(menu.getId())) {
                menu.setVisible(2); // 2 = checked
            }
            if (menu.getChildren() != null) {
                markChecked(menu.getChildren(), checkedIds);
            }
        }
    }

    @Override
    public List<SysMenu> getMenusByUserId(Long userId) {
        LambdaQueryWrapper<SysUserRole> urWrapper = new LambdaQueryWrapper<>();
        urWrapper.eq(SysUserRole::getUserId, userId);
        List<Long> roleIds = userRoleMapper.selectList(urWrapper).stream()
                .map(SysUserRole::getRoleId).collect(Collectors.toList());

        if (roleIds.isEmpty()) return List.of();

        Set<Long> menuIds = new HashSet<>();
        for (Long roleId : roleIds) {
            LambdaQueryWrapper<SysRoleMenu> rmWrapper = new LambdaQueryWrapper<>();
            rmWrapper.eq(SysRoleMenu::getRoleId, roleId);
            roleMenuMapper.selectList(rmWrapper).forEach(rm -> menuIds.add(rm.getMenuId()));
        }

        if (menuIds.isEmpty()) return List.of();

        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(SysMenu::getId, menuIds)
               .in(SysMenu::getMenuType, List.of("M", "C"))
               .eq(SysMenu::getVisible, 1)
               .eq(SysMenu::getStatus, 1)
               .orderByAsc(SysMenu::getSortOrder);
        List<SysMenu> menus = baseMapper.selectList(wrapper);
        return buildTree(menus, 0L);
    }

    private List<SysMenu> buildTree(List<SysMenu> list, Long parentId) {
        return list.stream()
                .filter(m -> m.getParentId().equals(parentId))
                .peek(m -> m.setChildren(buildTree(list, m.getId())))
                .collect(Collectors.toList());
    }
}
