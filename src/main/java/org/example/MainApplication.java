package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 🌟 开启全局定时任务支持
public class MainApplication {
    public static void main(String[] args) {
        // 这行代码才是启动 8080 端口和 Web 服务的灵魂！
        SpringApplication.run(MainApplication.class, args);
        System.out.println("✅ Spring Boot Web 服务器已在 8080 端口启动！");
    }
}