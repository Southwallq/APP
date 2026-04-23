package org.example;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/news")

public class NewsController {

    private final NewsSearchService newsSearchService;
    private final DeepSeekService deepSeekService;

    // ====================== 构造函数：启动时插入模拟数据 ======================
    public NewsController(NewsSearchService newsSearchService, DeepSeekService deepSeekService) {
        this.newsSearchService = newsSearchService;
        this.deepSeekService = deepSeekService;

        // 启动时插入数据
        initMockData();
    }

    private void initMockData() {
        // 删除或注释掉这段：
        // Map<String, Object> existing = newsSearchService.searchNews("", "", "", null, null, 0, 10);
        // List<?> list = (List<?>) existing.get("content");
        // if (list != null && !list.isEmpty()) {
        //     System.out.println("已有数据，跳过模拟数据插入");
        //     return;
        // }

        // ✅ 直接清空并插入
        newsSearchService.clearAll();

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.format.DateTimeFormatter formatter =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        createAndSaveNews("news-001", "OpenAI 发布 GPT-5...", "...", "人工智能", "机器之心", now.format(formatter));
        createAndSaveNews("news-002", "英伟达发布 H200...", "...", "芯片", "36氪", now.minusDays(2).format(formatter));
        createAndSaveNews("news-003", "宁德时代发布凝聚态电池...", "...", "新能源", "新浪科技", now.minusDays(10).format(formatter));
        createAndSaveNews("news-004", "SpaceX 星舰第六次...", "...", "航空航天", "虎嗅", now.minusDays(15).format(formatter));
        createAndSaveNews("news-005", "中科院发布量子计算...", "...", "量子计算", "科技日报", now.minusDays(35).format(formatter));

        System.out.println("✅ 模拟新闻数据已插入：5 条（时间分布：今天、2天前、10天前、15天前、35天前）");
    }

    private void createAndSaveNews(String id, String title, String summary,
                                   String topic, String source, String timeStr) {
        NewsDoc doc = new NewsDoc();
        doc.setId(id);
        doc.setTitle(title);
        doc.setSummary(summary);
        doc.setContent("<p>" + summary + "</p><p>详细内容：这是关于" + title + "的完整报道...</p>");
        doc.setTopic(topic);
        doc.setSource(source);
        // ✅ 修复：String 转 LocalDateTime
        doc.setPublishTime(LocalDateTime.parse(timeStr));
        doc.setViewCount((long)(Math.random() * 20000 + 5000));
        doc.setCoverImageUrl("https://picsum.photos/400/300?random=" + id);
        newsSearchService.saveNews(doc);
    }

    // ... 其他原有方法（test-es, search, detail, chat, save, batch-save）不变 ...

    @GetMapping("/test-es")
    public Map<String, Object> test() {
        return Map.of("code", 200, "msg", "✅ 服务已启动", "data", true);
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> params) {
        String keyword = (String) params.getOrDefault("keyword", "");
        String topic = (String) params.getOrDefault("topic", "");
        String source = (String) params.getOrDefault("source", "");
        String startTime = (String) params.getOrDefault("startTime", "");  // ✅ 新增
        String endTime = (String) params.getOrDefault("endTime", "");      // ✅ 新增
        int pageNum = (int) params.getOrDefault("pageNum", 0);
        int pageSize = (int) params.getOrDefault("pageSize", 10);

        Map<String, Object> data = newsSearchService.searchNews(
                keyword, topic, source, startTime, endTime, pageNum, pageSize  // ✅ 传递时间
        );
        return Map.of("code", 200, "msg", "成功", "data", data);
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam String id) {
        NewsDoc doc = newsSearchService.findById(id);
        if (doc == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 404);
            result.put("msg", "新闻不存在");
            result.put("data", null);
            return result;
        }
        return Map.of("code", 200, "msg", "成功", "data", doc);
    }

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

        return Map.of(
                "code", 200,
                "msg", "成功",
                "data", Map.of("answer", answer, "sessionId", UUID.randomUUID().toString())
        );
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody NewsDoc doc) {
        newsSearchService.saveNews(doc);
        return Map.of("code", 200, "msg", "保存成功");
    }

    @PostMapping("/batch-save")
    public Map<String, Object> batchSave(@RequestBody List<NewsDoc> list) {
        newsSearchService.batchSaveNews(list);
        return Map.of("code", 200, "msg", "批量保存成功");
    }
}
