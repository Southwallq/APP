package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        System.out.println("正在启动 Spring 容器...");

        try {
            SpringApplication app = new SpringApplication(DemoApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            ConfigurableApplicationContext context = app.run(args);

            // 尝试获取 Bean，如果这里报错，说明是 Bean 创建失败
            DeepSeekService service = context.getBean(DeepSeekService.class);

            System.out.println("✅ 启动成功！");
            System.out.println("------------------------------------------------");
            System.out.println("🤖 DeepSeek 聊天机器人已启动！");
            System.out.println("------------------------------------------------");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("\n👤 我: ");
                String input = scanner.nextLine();
                if ("exit".equalsIgnoreCase(input)) break;
                if (input.trim().isEmpty()) continue;

                System.out.println("🤖 AI: " + service.chat(input));
            }
            context.close();

        } catch (Exception e) {
            System.err.println("❌ 启动失败，详细原因如下：");
            e.printStackTrace(); // 这行代码会打印出真正的错误信息
        }
    }
}