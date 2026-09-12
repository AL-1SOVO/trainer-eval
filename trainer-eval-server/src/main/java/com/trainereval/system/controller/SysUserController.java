package com.trainereval.system.controller;

import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.system.dto.UserForm;
import com.trainereval.system.dto.UserQuery;
import com.trainereval.system.entity.SysUser;
import com.trainereval.system.service.SysUserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sys/users")
public class SysUserController {

    @Autowired
    private SysUserService userService;

    @GetMapping
    @PreAuthorize("hasRole('MANAGER')")
    public R<PageResult<SysUser>> list(UserQuery query) {
        return R.ok(userService.pageUsers(query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<SysUser> getById(@PathVariable Long id) {
        return R.ok(userService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> create(@Valid @RequestBody UserForm form) {
        userService.createUser(form);
        return R.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> update(@PathVariable Long id, @Valid @RequestBody UserForm form) {
        userService.updateUser(id, form);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> delete(@PathVariable Long id) {
        userService.updateStatus(id, 0);
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        userService.updateStatus(id, body.get("status"));
        return R.ok();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> assignRoles(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        userService.assignRoles(id, body.get("roleIds"));
        return R.ok();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        userService.resetPassword(id, body.get("password"));
        return R.ok();
    }
}
