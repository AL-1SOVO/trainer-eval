package com.trainereval.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.trainereval.system.entity.SysRole;
import java.util.List;

public interface SysRoleService extends IService<SysRole> {
    List<SysRole> listAll();
    void assignMenus(Long roleId, List<Long> menuIds);
    List<Long> getMenuIdsByRoleId(Long roleId);
}
