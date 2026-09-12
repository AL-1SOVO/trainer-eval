package com.trainereval.system.controller;

import com.trainereval.common.result.R;
import com.trainereval.system.entity.SysRole;
import com.trainereval.system.service.SysRoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sys/roles")
public class SysRoleController {

    @Autowired
    private SysRoleService roleService;

    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public R<List<SysRole>> list() {
        return R.ok(roleService.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<SysRole> getById(@PathVariable Long id) {
        return R.ok(roleService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> create(@RequestBody SysRole role) {
        roleService.save(role);
        return R.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> update(@PathVariable Long id, @RequestBody SysRole role) {
        role.setId(id);
        roleService.updateById(role);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> delete(@PathVariable Long id) {
        roleService.removeById(id);
        return R.ok();
    }

    @PutMapping("/{id}/menus")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> assignMenus(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        roleService.assignMenus(id, body.get("menuIds"));
        return R.ok();
    }
}
