package com.tian.erp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ERP 服务启动入口，扫描应用组件及 MyBatis-Plus Mapper。
 */
@SpringBootApplication
@MapperScan("com.tian.erp.mapper")
public class ErpApplication {

/**
 * 启动 Spring Boot 并传递命令行参数。
 */
	public static void main(String[] args) {
		SpringApplication.run(ErpApplication.class, args);
	}

}
