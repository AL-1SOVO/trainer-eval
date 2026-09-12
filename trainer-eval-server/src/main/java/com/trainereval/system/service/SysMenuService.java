package com.trainereval.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.trainereval.system.entity.SysMenu;
import java.util.List;

public interface SysMenuService extends IService<SysMenu> {
    List<SysMenu> getMenuTree();
    List<SysMenu> getRoleMenuTree(Long roleId);
    List<SysMenu> getMenusByUserId(Long userId);
}
