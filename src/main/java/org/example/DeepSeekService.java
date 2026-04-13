package org.example;

import java.util.Scanner;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.*; // 导入 okhttp3 包，但不单独导入 RequestBody
import org.springframework.web.bind.annotation.*; // 导入 Spring 注解

import java.io.IOException;

@RestController
@RequestMapping("/api/deepseek")
public class DeepSeekService {

    // ... (API_KEY 等配置保持不变) ...
    private static final String API_KEY = "sk-YOUR_DEEPSEEK_API_KEY";
    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private String userQuestion;

    @PostMapping("/chat")
    // 这里的 RequestBody 指的是 Spring 的注解
    public String chat(@org.springframework.web.bind.annotation.RequestBody String userQuestion) {
        this.userQuestion = userQuestion;
        System.out.println("收到用户提问: " + userQuestion);
        return callDeepSeekApi(userQuestion);
    }
    public String getDeepSeekAnswer(String question) {
        // 这里是你发送 OkHttp 请求的逻辑
        // ...
        return "回答内容";
    }
    private String callDeepSeekApi(String question) {
        OkHttpClient client = new OkHttpClient();

        // ... (构建 jsonBody 代码保持不变) ...
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", "deepseek-chat");
        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", question);
        messages.add(message);
        jsonBody.put("messages", messages);

        // 🔴 重点修改在这里 🔴
        // 必须写全 okhttp3.RequestBody，不能用简称
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                jsonBody.toJSONString(),
                okhttp3.MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body) // 这里传入上面的 body
                .build();

        // ... (后续执行请求的代码保持不变) ...
        try (Response response = client.newCall(request).execute()) {
            // ...
            return response.body().string(); // 简化演示
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }
}