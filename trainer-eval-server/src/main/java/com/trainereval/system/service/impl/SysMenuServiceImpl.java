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
        List<SysMenu> menus = new ArrayList<>(baseMapper.selectList(wrapper));

        // 修复：补齐"父节点不在授权集合里"的祖先菜单。
        // 原实现直接 buildTree(menus, 0L) 只挑 parentId==0 的节点，
        // 而 sys_role_menu 通常只授叶子菜单（如 ROLE_EMPLOYEE 只有 menu 6「AI学习助手」，
        // 其 parent_id=1「员工门户」并未授予）→ 整棵子树被丢弃 → 侧边栏空白，
        // 员工登录后看不到任何菜单（包括 AI 学习助手入口）。
        // 这里向上补齐祖先，让层级结构完整。
        Set<Long> present = menus.stream().map(SysMenu::getId).collect(Collectors.toSet());
        boolean grew = true;
        while (grew) {
            grew = false;
            Set<Long> missingParentIds = menus.stream()
                    .map(SysMenu::getParentId)
                    .filter(pid -> pid != null && pid != 0L && !present.contains(pid))
                    .collect(Collectors.toSet());
            if (missingParentIds.isEmpty()) {
                break;
            }
            for (Long pid : missingParentIds) {
                present.add(pid);           // 无论祖先是否可用，都先标记已处理，避免死循环
                SysMenu parent = baseMapper.selectById(pid);
                if (parent == null) {
                    continue;
                }
                // 祖先同样按目录/菜单 + 可见 + 启用过滤，避免把停用目录带出来
                boolean usable = parent.getMenuType() != null
                        && ("M".equals(parent.getMenuType()) || "C".equals(parent.getMenuType()))
                        && Integer.valueOf(1).equals(parent.getVisible())
                        && Integer.valueOf(1).equals(parent.getStatus());
                if (usable) {
                    menus.add(parent);
                    grew = true;
                }
            }
        }

        return buildTree(menus, 0L);
    }

    private List<SysMenu> buildTree(List<SysMenu> list, Long parentId) {
        Set<Long> ids = list.stream().map(SysMenu::getId).collect(Collectors.toSet());
        return list.stream()
                // 父节点不在集合里的一律当作根节点，保证任何情况下都不会整棵子树消失
                .filter(m -> parentId == 0L
                        ? (m.getParentId() == null || m.getParentId() == 0L || !ids.contains(m.getParentId()))
                        : parentId.equals(m.getParentId()))
                .peek(m -> m.setChildren(buildTree(list, m.getId())))
                .collect(Collectors.toList());
    }
}
