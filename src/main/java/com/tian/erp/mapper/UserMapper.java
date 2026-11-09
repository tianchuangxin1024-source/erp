package com.tian.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.erp.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 继承 MyBatis-Plus 的历史用户档案数据访问接口。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 继承 BaseMapper 后，无需编写 XML 即可拥有基本的 CRUD 方法
    // 如需自定义复杂 SQL，可在此处定义方法并在 XML 中实现
}
