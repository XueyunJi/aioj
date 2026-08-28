package com.aioj.next.problem.persistence.mapper;

import com.aioj.next.problem.persistence.entity.UserAccountEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {
}
