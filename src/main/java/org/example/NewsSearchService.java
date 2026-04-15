package org.example;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NewsSearchService {

    // 内存数据库（代替 ES）
    private final List<NewsDoc> memoryStorage = new ArrayList<>();

    // ====================== 爬虫入库 ======================
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

    // ====================== 全文检索（模拟ES功能） ======================
    public Map<String, Object> searchNews(
            String keyword,
            String topic,
            String source,
            String startTime,
            String endTime,
            int pageNum,
            int pageSize
    ) {
        List<NewsDoc> filtered = memoryStorage.stream()
                .filter(news -> {
                    boolean match = true;
                    if (keyword != null && !keyword.isBlank()) {
                        match = (news.getTitle() != null && news.getTitle().contains(keyword))
                                || (news.getContent() != null && news.getContent().contains(keyword));
                    }
                    if (topic != null && !topic.isBlank()) {
                        match &= topic.equals(news.getTopic());
                    }
                    if (source != null && !source.isBlank()) {
                        match &= source.equals(news.getSource());
                    }
                    return match;
                })
                .collect(Collectors.toList());

        // 分页
        int total = filtered.size();
        int start = Math.min(pageNum * pageSize, total);
        int end = Math.min(start + pageSize, total);
        List<NewsDoc> pageList = filtered.subList(start, end);

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("list", pageList);
        return result;
    }

    // ====================== 测试连通性 ======================
    public boolean testEsConnection() {
        return true; // 永远成功！
    }
}