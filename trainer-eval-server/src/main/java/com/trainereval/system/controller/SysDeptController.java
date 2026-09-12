package com.trainereval.system.controller;

import com.trainereval.common.result.R;
import com.trainereval.system.entity.SysDept;
import com.trainereval.system.service.SysDeptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sys/depts")
public class SysDeptController {

    @Autowired
    private SysDeptService deptService;

    @GetMapping("/tree")
    @PreAuthorize("hasAnyRole('MANAGER', 'TRAINER')")
    public R<List<SysDept>> tree() {
        return R.ok(deptService.getDeptTree());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<SysDept> getById(@PathVariable Long id) {
        return R.ok(deptService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> create(@RequestBody SysDept dept) {
        deptService.save(dept);
        return R.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> update(@PathVariable Long id, @RequestBody SysDept dept) {
        dept.setId(id);
        deptService.updateById(dept);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<?> delete(@PathVariable Long id) {
        deptService.removeById(id);
        return R.ok();
    }
}
