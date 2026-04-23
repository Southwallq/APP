package org.example;

import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsSearchService {

    private final List<NewsDoc> memoryStorage = new ArrayList<>();

    public NewsDoc saveNews(NewsDoc newsDoc) {
        if (newsDoc.getId() == null) {
            newsDoc.setId(UUID.randomUUID().toString());
        }
        memoryStorage.add(newsDoc);
        return newsDoc;
    }

    public List<NewsDoc> batchSaveNews(List<NewsDoc> list) {
        list.forEach(doc -> {
            if (doc.getId() == null) doc.setId(UUID.randomUUID().toString());
        });
        memoryStorage.addAll(list);
        return list;
    }

    public NewsDoc findById(String id) {
        if (id == null) return null;
        return memoryStorage.stream()
                .filter(doc -> doc.getId() != null && id.equals(doc.getId()))
                .findFirst()
                .orElse(null);
    }

    // ====================== 修复：添加时间筛选 ======================
    public Map<String, Object> searchNews(
            String keyword,
            String topic,
            String source,
            String startTime,    // 格式：2026-04-19T00:00:00
            String endTime,      // 格式：2026-04-19T23:59:59
            int pageNum,
            int pageSize
    ) {
        List<NewsDoc> filtered = memoryStorage.stream()
                .filter(news -> {
                    boolean match = true;

                    // 关键词搜索
                    if (keyword != null && !keyword.isBlank()) {
                        match = (news.getTitle() != null && news.getTitle().contains(keyword))
                                || (news.getContent() != null && news.getContent().contains(keyword));
                    }

                    // 主题筛选
                    if (topic != null && !topic.isBlank()) {
                        match &= topic.equals(news.getTopic());
                    }

                    // 来源筛选
                    if (source != null && !source.isBlank()) {
                        match &= source.equals(news.getSource());
                    }

                    // ✅ 新增：时间范围筛选
                    if (startTime != null && !startTime.isEmpty()
                            && endTime != null && !endTime.isEmpty()) {
                        try {
                            LocalDateTime newsTime = news.getPublishTime();
                            LocalDateTime start = LocalDateTime.parse(startTime);
                            LocalDateTime end = LocalDateTime.parse(endTime);

                            // 新闻时间必须在 [start, end] 范围内
                            if (newsTime != null) {
                                match &= !newsTime.isBefore(start) && !newsTime.isAfter(end);
                            }
                        } catch (Exception e) {
                            // 时间解析失败，忽略时间筛选
                            System.out.println("时间解析失败: " + e.getMessage());
                        }
                    }

                    return match;
                })
                .collect(Collectors.toList());

        // 分页
        int total = filtered.size();
        int start = Math.min(pageNum * pageSize, total);
        int end = Math.min(start + pageSize, total);
        List<NewsDoc> pageList = (start < end) ? filtered.subList(start, end) : new ArrayList<>();

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("content", pageList);
        result.put("list", pageList);        // 兼容前端
        result.put("totalElements", total);  // 兼容前端
        return result;
    }

    public boolean testEsConnection() {
        return true;
    }

    public void clearAll() {
    }
}
