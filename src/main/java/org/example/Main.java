package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType; // 1. 必须导入这个枚举
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        System.out.println("正在启动 Spring 容器...");

        // 2. 创建 SpringApplication 实例
        SpringApplication app = new SpringApplication(DemoApplication.class);

        // 3. 【关键步骤】强制设置为非 Web 应用，这样就不会去检查什么 WebServerFactory 了
        app.setWebApplicationType(WebApplicationType.NONE);

        // 4. 启动容器
        ConfigurableApplicationContext context = app.run(args);

        // 5. 获取 Service
        DeepSeekService service = context.getBean(DeepSeekService.class);

        System.out.println("------------------------------------------------");
        System.out.println("🤖 DeepSeek 聊天机器人已启动！");
        System.out.println("👉 请输入内容（输入 'exit' 退出）：");
        System.out.println("------------------------------------------------");

        // 6. 循环对话
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\n👤 我: ");
            String input = scanner.nextLine();

            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                System.out.println("👋 再见！");
                break;
            }

            if (input.trim().isEmpty()) continue;

            try {
                String reply = service.chat(input);
                System.out.println("🤖 AI: " + reply);
            } catch (Exception e) {
                System.err.println("❌ 出错了: " + e.getMessage());
                e.printStackTrace();
            }
        }

        context.close();
        scanner.close();
    }
}