package org.example;

import org.springframework.web.bind.annotation.*;
import java.util.*;

// 🌟 新增：MongoDB 相关的依赖包
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import com.mongodb.client.model.Filters;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsSearchService newsSearchService;
    private final DeepSeekService deepSeekService;

    // 💡 构造函数注入
    public NewsController(NewsSearchService newsSearchService, DeepSeekService deepSeekService) {
        this.newsSearchService = newsSearchService;
        this.deepSeekService = deepSeekService;
    }

    /**
     * 新闻搜索接口 (Lucene 实现)
     */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> params) {
        String keyword = (String) params.getOrDefault("keyword", "");
        String topic = (String) params.getOrDefault("topic", "");
        String source = (String) params.getOrDefault("source", "");
        String startTime = (String) params.getOrDefault("startTime", "");
        String endTime = (String) params.getOrDefault("endTime", "");
        int pageNum = (int) params.getOrDefault("pageNum", 1);
        int pageSize = (int) params.getOrDefault("pageSize", 10);

        // 调用 Lucene 版本的搜索
        Map<String, Object> data = newsSearchService.searchNews(
                keyword, topic, source, startTime, endTime, pageNum, pageSize
        );
        return Map.of("code", 200, "msg", "成功", "data", data);
    }

    /**
     * 🌟 修复版：新闻详情接口 (查 MongoDB，带图片返回)
     */
    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam String id) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 1. 获取 MongoDB 集合 (利用你现成的 JDBC 工具类)
            MongoCollection<Document> collection = JDBC.getMongoCollection("news_content");

            // 2. 根据 ID 查询那条唯一的新闻
            Document doc = collection.find(Filters.eq("id", id)).first();

            if (doc != null) {
                // 成功从 MongoDB 拿到完整数据（包含 images 数组）
                result.put("code", 200);
                result.put("msg", "成功");
                result.put("data", doc);
            } else {
                result.put("code", 404);
                result.put("msg", "新闻不存在");
                result.put("data", null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "服务器内部错误");
        }
        return result;
    }

    /**
     * AI 问答接口：基于单篇新闻
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> params) {
        String question = (String) params.get("question");
        String newsId = (String) params.get("newsId");

        String prompt = question;
        if (newsId != null && !newsId.isEmpty()) {
            NewsDoc doc = newsSearchService.findById(newsId);
            if (doc != null) {
                prompt = "基于新闻《" + doc.getTitle() + "》回答：" + question;
            }
        }

        String answer = deepSeekService.chat(prompt);
        return Map.of("code", 200, "msg", "成功", "data", Map.of("answer", answer));
    }

    /**
     * 🌟 RAG 问答：基于 Lucene 全库检索
     */
    @PostMapping("/ask-db")
    public Map<String, Object> askDatabase(@RequestBody Map<String, Object> params) {
        String question = (String) params.get("question");
        if (question == null || question.trim().isEmpty()) {
            return Map.of("code", 400, "msg", "问题不能为空");
        }

        // 1. 利用 Lucene 进行相关性检索
        Map<String, Object> searchResult = newsSearchService.searchNews(
                question, null, null, null, null, 1, 5
        );

        List<NewsDoc> relatedNews = (List<NewsDoc>) searchResult.get("list");

        // 2. 组装 Prompt (开卷考试模式)
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("你是一个科技新闻助手。请基于以下检索到的新闻库内容回答问题：\n\n");

        if (relatedNews != null && !relatedNews.isEmpty()) {
            for (int i = 0; i < relatedNews.size(); i++) {
                NewsDoc doc = relatedNews.get(i);
                promptBuilder.append("【来源 ").append(i + 1).append("】").append(doc.getTitle()).append("\n");
                // 截取摘要，防止 Prompt 太长
                String content = doc.getContent();
                if (content != null && content.length() > 300) content = content.substring(0, 300) + "...";
                promptBuilder.append("内容：").append(content).append("\n\n");
            }
        }

        promptBuilder.append("用户问题：").append(question).append("\n作答：");

        // 3. 调用 AI
        String answer = deepSeekService.chat(promptBuilder.toString());

        return Map.of(
                "code", 200,
                "msg", "成功",
                "data", Map.of("answer", answer, "referenceCount", relatedNews != null ? relatedNews.size() : 0)
        );
    }
}