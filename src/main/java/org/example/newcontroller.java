package org.example;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/news")
@CrossOrigin(origins = "*") // 🌟 关键：允许跨域请求，前端独立部署时必备
public class newcontroller {

    @Autowired
    private NewsSearchService newsSearchService;

    /**
     * 综合搜索接口
     * 请求方法: GET
     * 请求URL: http://服务器IP:端口/api/news/search
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchNews(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int pageNum,    // 默认第一页
            @RequestParam(defaultValue = "10") int pageSize   // 默认每页10条
    ) {

        Map<String, Object> searchResult = newsSearchService.searchNews(
                keyword, topic, source, startTime, endTime, pageNum, pageSize
        );

        // 返回 200 OK 状态码以及 JSON 数据
        return ResponseEntity.ok(searchResult);
    }
    /**
     * 🌟 获取文章的分类导航 (Topic) 和 来源导航 (Source)
     */
    @GetMapping("/filters")
    public Map<String, Object> getFilters() {
        List<String> topics = newsSearchService.getTerms("topic");
        List<String> sources = newsSearchService.getTerms("source");
        return Map.of("code", 200, "msg", "成功", "data", Map.of(
                "topics", topics,
                "sources", sources
        ));
    }
}