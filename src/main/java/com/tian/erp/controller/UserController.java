package com.tian.erp.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tian.erp.common.response.ApiResponse;
import com.tian.erp.entity.User;
import com.tian.erp.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提供历史用户档案接口，持久化到 user_tab。
 */
@Tag(name = "05 历史用户档案")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    /**
     * 注入历史用户档案服务。
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 查询所有用户
     * GET /api/users
     */
    @Operation(summary = "查询历史用户档案", description = "data 为用户档案数组。此接口使用 user_tab，与财务登录账号不同。")
    @GetMapping
    public ApiResponse<List<User>> listUsers() {
        return ApiResponse.ok(userService.list());
    }

    /**
     * 根据ID查询用户
     * GET /api/users/{id}
     */
    @Operation(summary = "查询历史用户详情", description = "data 为用户档案；记录不存在时仍为 HTTP 200 且 data=null。")
    @GetMapping("/{id}")
    public ApiResponse<User> getUserById(@PathVariable Long id) {
        return ApiResponse.ok(userService.getById(id));
    }

    /**
     * 新增用户
     * POST /api/users
     */
    @Operation(summary = "新增历史用户档案", description = "请求字段 name、age、gender、hobby。返回 data 布尔值。")
    @PostMapping
    public ApiResponse<Boolean> addUser(@RequestBody User user) {
        return ApiResponse.ok(userService.save(user));
    }

    /**
     * 更新用户
     * PUT /api/users
     */
    @Operation(summary = "更新历史用户档案", description = "请求需 id，以及待更新的 name、age、gender、hobby。返回 data 布尔值。")
    @PutMapping
    public ApiResponse<Boolean> updateUser(@RequestBody User user) {
        return ApiResponse.ok(userService.updateById(user));
    }

    /**
     * 删除用户
     * DELETE /api/users/{id}
     */
    @Operation(summary = "删除历史用户档案", description = "返回 data 布尔值，记录不存在时通常为 false。")
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> deleteUser(@PathVariable Long id) {
        return ApiResponse.ok(userService.removeById(id));
    }

    /**
     * 条件查询示例：根据姓名模糊查询
     * GET /api/users/search?name=张
     */
    @Operation(summary = "按姓名搜索历史用户", description = "可选 name，采用姓名模糊查询，未提供时查询全部。")
    @GetMapping("/search")
    public ApiResponse<List<User>> searchUsers(@RequestParam(required = false) String name) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        if (name != null && !name.isEmpty()) {
            queryWrapper.like(User::getName, name);
        }
        return ApiResponse.ok(userService.list(queryWrapper));
    }
}
