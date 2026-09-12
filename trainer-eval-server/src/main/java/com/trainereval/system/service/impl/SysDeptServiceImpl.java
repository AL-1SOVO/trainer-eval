package com.trainereval.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.trainereval.system.entity.SysDept;
import com.trainereval.system.mapper.SysDeptMapper;
import com.trainereval.system.service.SysDeptService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements SysDeptService {

    @Override
    public List<SysDept> getDeptTree() {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDept::getStatus, 1).orderByAsc(SysDept::getSortOrder);
        List<SysDept> all = baseMapper.selectList(wrapper);
        return buildTree(all, 0L);
    }

    private List<SysDept> buildTree(List<SysDept> list, Long parentId) {
        return list.stream()
                .filter(d -> d.getParentId().equals(parentId))
                .peek(d -> d.setChildren(buildTree(list, d.getId())))
                .collect(Collectors.toList());
    }
}
