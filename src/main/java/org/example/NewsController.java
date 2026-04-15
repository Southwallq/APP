package org.example;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsSearchService newsSearchService;

    // 测试连通
    @GetMapping("/test-es")
    public Map<String, Object> test() {
        return Map.of(
                "code", 200,
                "msg", "✅ 内存检索服务已启动（无需ES）",
                "data", true
        );
    }

    // 全文检索
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> params) {
        String keyword = (String) params.getOrDefault("keyword", "");
        String topic = (String) params.getOrDefault("topic", "");
        String source = (String) params.getOrDefault("source", "");
        int pageNum = (int) params.getOrDefault("pageNum", 0);
        int pageSize = (int) params.getOrDefault("pageSize", 10);

        Map<String, Object> data = newsSearchService.searchNews(
                keyword, topic, source, null, null, pageNum, pageSize
        );
        return Map.of(
                "code", 200,
                "msg", "成功",
                "data", data
        );
    }

    // 爬虫入库
    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody NewsDoc doc) {
        newsSearchService.saveNews(doc);
        return Map.of("code", 200, "msg", "保存成功");
    }

    // 批量入库
    @PostMapping("/batch-save")
    public Map<String, Object> batchSave(@RequestBody java.util.List<NewsDoc> list) {
        newsSearchService.batchSaveNews(list);
        return Map.of("code", 200, "msg", "批量保存成功");
    }
}