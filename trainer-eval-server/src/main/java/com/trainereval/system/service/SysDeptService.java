package com.trainereval.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.trainereval.system.entity.SysDept;
import java.util.List;

public interface SysDeptService extends IService<SysDept> {
    List<SysDept> getDeptTree();
}
