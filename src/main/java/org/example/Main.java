package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        // 启动 Spring Boot 服务器
        ApplicationContext context = SpringApplication.run(Main.class, args);

        // 获取 ES 服务（自动判断是否连接成功）
        NewsSearchService newsSearchService = context.getBean(NewsSearchService.class);
        boolean esOk = newsSearchService.testEsConnection();

        // 启动成功提示（保持你原来的风格）
        System.out.println("========================================");
        System.out.println("✅ 服务器启动成功！");
        System.out.println("📌 AI 聊天接口地址：POST http://localhost:8080/ai/chat");
        System.out.println("📌 新闻检索接口：POST http://localhost:8080/api/news/search");

        // 自动显示 ES 状态
        if (esOk) {
            System.out.println("✅ ES 检索服务已连接");
        } else {
            System.out.println("⚠️  使用内存检索（无需安装ES）");
        }

        System.out.println("📌 接口已封装完成，可直接部署到服务器");
        System.out.println("========================================");
    }
}