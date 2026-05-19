package org.example;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

@Service
public class DeepSeekService {
    private static final String API_KEY = "sk-7aefe0eb3fad401faae9ded23d2933e2";
    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";

    public DeepSeekService() {
    }

    public String chat(String userQuestion) {
        try {
            return this.getDeepSeekAnswer(userQuestion);
        } catch (Exception e) {
            return "AI 服务异常：" + e.getMessage();
        }
    }

    public String getDeepSeekAnswer(String question) {
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", "deepseek-chat");
        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", question);
        messages.add(message);
        jsonBody.put("messages", messages);
        jsonBody.put("temperature", 0.7);
        jsonBody.put("stream", false);
        RequestBody body = RequestBody.create(jsonBody.toJSONString(new JSONWriter.Feature[0]), MediaType.parse("application/json; charset=utf-8"));
        Request request = (new Request.Builder()).url("https://api.deepseek.com/v1/chat/completions").addHeader("Authorization", "Bearer sk-7aefe0eb3fad401faae9ded23d2933e2").addHeader("Content-Type", "application/json").post(body).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) {
                return "DeepSeek 返回为空";
            } else {
                String result = response.body().string();
                JSONObject jsonObject = JSONObject.parseObject(result);
                return jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            }
        } catch (IOException e) {
            return "请求失败：" + e.getMessage();
        }
    }
}