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
     * 🌟 RAG 问答：基于 Lucene 全库检索 (附带【时间意图感知】和【强制溯源链接】)
     */
    @PostMapping("/ask-db")
    public Map<String, Object> askDatabase(@RequestBody Map<String, Object> params) {
        String question = (String) params.get("question");
        if (question == null || question.trim().isEmpty()) {
            return Map.of("code", 400, "msg", "问题不能为空");
        }

        // 🌟🌟🌟 核心进化 1：简单的 NLP 意图识别，判断用户是否要找“最新”新闻
        boolean wantsLatest = false;
        String searchKeyword = question;

        if (question.contains("最新") || question.contains("最近") || question.contains("今天") || question.contains("今日")) {
            wantsLatest = true;
            // 把时间修饰词剔除，防止 Lucene 去死磕“最新”这两个字
            searchKeyword = searchKeyword.replaceAll("最新|最近|今天|今日|的|新闻", "").trim();
        }

        // 🌟🌟🌟 核心进化 2：利用 Lucene 检索 (如果是查最新，我们就多捞一点，比如捞 20 条出来排个序)
        int fetchSize = wantsLatest ? 20 : 5;
        Map<String, Object> searchResult = newsSearchService.searchNews(
                searchKeyword.isEmpty() ? null : searchKeyword, // 如果去掉了词变成了空，就传 null 查全库
                null, null, null, null, 1, fetchSize
        );

        List<NewsDoc> relatedNews = (List<NewsDoc>) searchResult.get("list");

        // 🌟🌟🌟 核心进化 3：如果用户要最新的，我们在 Java 内存里按时间给它降序排个序！
        if (wantsLatest && relatedNews != null && !relatedNews.isEmpty()) {
            relatedNews.sort((a, b) -> {
                String timeA = a.getPublishTime() != null ? a.getPublishTime() : "";
                String timeB = b.getPublishTime() != null ? b.getPublishTime() : "";
                // 字符串降序排列 (时间越近越靠前)
                return timeB.compareTo(timeA);
            });
        }

        // 🌟 核心进化 4：精选前 5 条喂给 AI (防止数据太多把大模型的 Prompt 撑爆)
        if (relatedNews != null && relatedNews.size() > 5) {
            relatedNews = relatedNews.subList(0, 5);
        }

        // 组装 Prompt (开卷考试模式)
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("你是一个专业的前沿科技新闻助手。请基于以下检索到的新闻库内容回答问题。\n");
        promptBuilder.append("【重要格式指令】：当你在回答中引用、推荐或总结了某篇新闻时，必须在句尾附上该新闻的溯源链接！\n");
        promptBuilder.append("你必须严格使用 Markdown 的链接语法，格式为：`[真实的新闻标题](真实的新闻ID)`。\n");
        promptBuilder.append("注意：括号内只能填我提供给你的纯数字 ID，绝对不要使用完整的 http 网址！\n");
        promptBuilder.append("正确示例：`详情请看：[马斯克发布新火箭](314159)`\n\n");

        if (relatedNews != null && !relatedNews.isEmpty()) {
            promptBuilder.append("--- 检索到的新闻库资料（请严格从中提取信息和ID） ---\n");
            for (int i = 0; i < relatedNews.size(); i++) {
                NewsDoc doc = relatedNews.get(i);
                promptBuilder.append("【来源 ").append(i + 1).append("】\n");
                promptBuilder.append("真实的新闻ID：").append(doc.getId()).append("\n");
                promptBuilder.append("真实的新闻标题：").append(doc.getTitle()).append("\n");
                // 🌟🌟🌟 把文章时间也交底给 AI，让它回答时能有理有据！
                promptBuilder.append("发布时间：").append(doc.getPublishTime()).append("\n");

                String content = doc.getContent();
                if (content != null && content.length() > 300) content = content.substring(0, 300) + "...";
                promptBuilder.append("内容：").append(content).append("\n\n");
            }
        }

        promptBuilder.append("用户问题：").append(question).append("\n作答：");

        // 调用 AI 核心大脑
        String answer = deepSeekService.chat(promptBuilder.toString());

        return Map.of(
                "code", 200,
                "msg", "成功",
                "data", Map.of("answer", answer, "referenceCount", relatedNews != null ? relatedNews.size() : 0)
        );
    }
}