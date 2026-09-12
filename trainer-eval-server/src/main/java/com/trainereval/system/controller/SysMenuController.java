package com.trainereval.system.controller;

import com.trainereval.common.result.R;
import com.trainereval.system.entity.SysMenu;
import com.trainereval.system.service.SysMenuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sys/menus")
public class SysMenuController {

    @Autowired
    private SysMenuService menuService;

    @GetMapping("/tree")
    @PreAuthorize("hasRole('MANAGER')")
    public R<List<SysMenu>> tree() {
        return R.ok(menuService.getMenuTree());
    }

    @GetMapping("/role-tree")
    @PreAuthorize("hasRole('MANAGER')")
    public R<List<SysMenu>> roleTree(@RequestParam Long roleId) {
        return R.ok(menuService.getRoleMenuTree(roleId));
    }

    @GetMapping("/user")
    public R<List<SysMenu>> userMenus() {
        Long userId = com.trainereval.common.util.SecurityUtils.getCurrentUserId();
        return R.ok(menuService.getMenusByUserId(userId));
    }

    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> create(@RequestBody SysMenu menu) {
        menuService.save(menu);
        return R.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> update(@PathVariable Long id, @RequestBody SysMenu menu) {
        menu.setId(id);
        menuService.updateById(menu);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> delete(@PathVariable Long id) {
        menuService.removeById(id);
        return R.ok();
    }
}
