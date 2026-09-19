package com.trainereval.observability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.trainereval.observability.AiTrace;
import org.apache.ibatis.annotations.Mapper;

/**
 * 链路追踪本地表 Mapper。
 * <p>
 * 注意包路径必须是 {@code com.trainereval.<模块>.mapper}：全局的
 * {@code @MapperScan("com.trainereval.**.mapper")} 只扫描名为 mapper 的子包，
 * 放在其他位置会得到"启动时找不到 Mapper Bean"的失败 —— 而且报错信息指向
 * 使用它的 Service，不看 MapperScan 很难反应过来。
 */
@Mapper
public interface AiTraceMapper extends BaseMapper<AiTrace> {
}
