package com.tian.erp.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tian.erp.entity.User;
import com.tian.erp.mapper.UserMapper;
import com.tian.erp.service.UserService;
import org.springframework.stereotype.Service;

/**
 * 将历史用户档案服务绑定到 UserMapper 和 User 实体。
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
}
