package com.tian.erp.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 历史用户档案实体，对应 user_tab 的个人资料字段。
 */
@Data
@TableName("user_tab")
@Schema(description = "历史用户档案，与财务登录账户分开维护")
public class User {
    /**
     * 主键ID，自增
     */
    @TableId(type = IdType.AUTO)
    @Schema(description = "档案主键，新增时省略", example = "1")
    private Long id;

    /**
     * 姓名
     */
    @Schema(description = "姓名", example = "张三")
    private String name;

    /**
     * 年龄
     */
    @Schema(description = "年龄", example = "30")
    private Integer age;

    /**
     * 性别
     */
    @Schema(description = "性别", example = "男")
    private String gender;

    /**
     * 爱好
     */
    @Schema(description = "爱好", example = "阅读")
    private String hobby;
}
