package org.example;

import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.pipeline.Pipeline;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.Spider;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PingWestCrawler - 2026版 (解耦版)
 * 职责：负责解析品玩网页内容，并通过 Pipeline 实现 MySQL + MongoDB + Lucene 三端同步
 */
public class PingWestCrawler implements PageProcessor {

    private Site site = Site.me()
            .setCharset("UTF-8")
            .setRetryTimes(3)
            .setSleepTime(1200)
            .setTimeOut(15000)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

    @Override
    public void process(Page page) {
        String url = page.getUrl().toString();
        String title = page.getHtml().xpath("//meta[@property='og:title']/@content").get();

        // 1. 标题清洗
        if (title != null && title.contains("-品玩")) {
            title = title.replace("-品玩", "").trim();
        }

        // 2. 时间校验 (只爬 2026 年的数据)
        String publishTime = extractPerfectPublishTime(page);
        if (publishTime == null || !publishTime.startsWith("2026")) {
            page.setSkip(true);
            return;
        }

        // 3. 正文清洗
        List<String> allParagraphs = page.getHtml().xpath("//body//p//text()").all();
        StringBuilder cleanContent = new StringBuilder();
        String[] filterKeywords = {
                "扫码查看", "每日要闻", "关注公众号", "点击查看", "阅读原文", "广告",
                "这家伙很懒，什么也没留下，却只想留下你！", "请「」后评论", "请「登录」后评论"
        };

        for (String p : allParagraphs) {
            String trimP = p.trim();
            if (trimP.length() < 5) continue;
            boolean isAd = false;
            for (String kw : filterKeywords) {
                if (trimP.contains(kw)) { isAd = true; break; }
            }
            if (!isAd) cleanContent.append(trimP).append("\n");
        }

        // 4. 图片提取
        List<String> articleImageUrls = new ArrayList<>();
        List<String> containerImgs = page.getHtml().xpath("//article//img/@src | //div[contains(@class,'article-content')]//img/@src").all();
        for (String imgUrl : containerImgs) {
            if (imgUrl != null && imgUrl.length() > 20) {
                if (imgUrl.startsWith("//")) imgUrl = "https:" + imgUrl;
                if (imgUrl.contains("pingwest.com") && !imgUrl.contains("logo") && !imgUrl.contains("avatar")) {
                    articleImageUrls.add(imgUrl);
                }
            }
        }

        // 5. 传给 Pipeline 处理
        if (title != null && !title.isEmpty()) {
            page.putField("title", title);
            page.putField("time", publishTime);
            page.putField("content", cleanContent.toString().trim());
            page.putField("url", url);
            page.putField("images", articleImageUrls);
            page.putField("source", "品玩");
        } else {
            page.setSkip(true);
        }
    }

    private String extractPerfectPublishTime(Page page) {
        String rawHtml = page.getRawText();
        Pattern pattern = Pattern.compile("(202[4-6])-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])");
        Matcher matcher = pattern.matcher(rawHtml);
        if (matcher.find()) {
            return matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
        }
        return null;
    }

    @Override
    public Site getSite() { return site; }

    // ======================= 🔥 三端同步管道 (MySQL + Mongo + Lucene) =======================
    public static class MultiDbPipeline implements Pipeline {
        private int count = 0;
        private final int targetLimit;
        private Spider spider;
        private Set<String> savedTitles = ConcurrentHashMap.newKeySet();

        private Connection mysqlConn;
        private MongoCollection<Document> mongoColl;

        private static final String INDEX_PATH = "/opt/news_lucene_index";
        private Analyzer analyzer = new SmartChineseAnalyzer();

        // 🌟 注入 AI 服务
        private final org.example.DeepSeekService deepSeekService;

        public MultiDbPipeline(int targetLimit, org.example.DeepSeekService deepSeekService) {
            this.targetLimit = targetLimit;
            this.deepSeekService = deepSeekService;
            try {
                this.mysqlConn = JDBC.getMysqlConnection();
                this.mongoColl = JDBC.getMongoCollection("news_content");
                System.out.println("✅ Pipeline 初始化成功：MySQL + MongoDB 已就绪");
                System.out.println("✅ Lucene 索引路径： " + INDEX_PATH);
            } catch (Exception e) {
                System.err.println("❌ Pipeline 初始化数据库失败: " + e.getMessage());
            }
        }

        public void setSpider(Spider spider) { this.spider = spider; }

        @Override
        public synchronized void process(ResultItems resultItems, Task task) {
            if (count >= targetLimit) return;

            String title = resultItems.get("title");
            if (title == null || savedTitles.contains(title)) return;
            savedTitles.add(title);

            try {
                String time = resultItems.get("time");
                String url = resultItems.get("url");
                String content = resultItems.get("content");
                List<String> images = resultItems.get("images");
                String source = resultItems.get("source");

                // 使用基于 URL 的固定 UUID，彻底杜绝数据冗余！
                String newsId = java.util.UUID.nameUUIDFromBytes(url.getBytes()).toString();

                // 🌟 【核心亮点：AI 智能打标签分类】
                String topic = "综合"; // 默认兜底分类
                if (deepSeekService != null) {
                    try {
                        String prompt = "请根据以下科技新闻标题，给出最匹配的一个领域分类标签。仅输出标签本身，不要任何标点，字数不超过4个字。" +
                                "可选标签：[人工智能, 智能汽车, 半导体, 消费电子, 互联网, 创投, 航天, 综合]。标题：《" + title + "》";
                        String aiResult = deepSeekService.chat(prompt);
                        if (aiResult != null && aiResult.length() <= 6) {
                            topic = aiResult.trim().replace("。", "").replace("\"", "").replace("”", "").replace("“", "");
                        }
                    } catch (Exception e) {
                        System.err.println("⚠️ AI 自动分类超时或异常，使用默认[综合]标签");
                    }
                }

                // 1. MySQL 写入 (存索引)
                if (mysqlConn != null) {
                    String sql = "INSERT IGNORE INTO news_data (title, url, publish_time) VALUES (?, ?, ?)";
                    try (PreparedStatement pstmt = mysqlConn.prepareStatement(sql)) {
                        pstmt.setString(1, title);
                        pstmt.setString(2, url);
                        pstmt.setString(3, time);
                        pstmt.executeUpdate();
                    }
                }

                // 2. MongoDB 写入 (存全量正文 + 包含 AI 算出来的 topic)
                if (mongoColl != null) {
                    Document mongoDoc = new Document("id", newsId)
                            .append("title", title)
                            .append("url", url)
                            .append("publish_time", time)
                            .append("content", content)
                            .append("images", images != null ? images : new ArrayList<>())
                            .append("source", source)
                            .append("topic", topic); // 🌟 存入 MongoDB

                    mongoColl.updateOne(
                            new Document("id", newsId),
                            new Document("$set", mongoDoc),
                            new com.mongodb.client.model.UpdateOptions().upsert(true)
                    );
                }

                // 3. Lucene 写入 (同步写入 AI 算出来的 topic)
                writeToLucene(newsId, title, content, time, source, url, topic);

                count++;
                System.out.println("🚀 [" + count + "/" + targetLimit + "] 三端同步入库成功 | 分类: [" + topic + "] | 标题: " + title);

                if (count >= targetLimit && spider != null) {
                    System.out.println("✨ 目标达成，正在停止爬虫...");
                    spider.stop();
                }
            } catch (Exception e) {
                System.err.println("❌ 写入过程发生异常: " + e.getMessage());
            }
        }

        /**
         * Lucene 写入逻辑
         */
        private void writeToLucene(String id, String title, String content, String time, String source, String url, String topic) {
            try (Directory dir = FSDirectory.open(Paths.get(INDEX_PATH))) {
                IndexWriterConfig config = new IndexWriterConfig(analyzer);
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

                try (IndexWriter writer = new IndexWriter(dir, config)) {
                    org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();

                    luceneDoc.add(new StringField("id", id, Field.Store.YES));
                    luceneDoc.add(new TextField("title", title, Field.Store.YES));
                    luceneDoc.add(new TextField("content", content, Field.Store.YES));
                    luceneDoc.add(new StringField("publish_time", time, Field.Store.YES));
                    luceneDoc.add(new SortedDocValuesField("publish_time", new BytesRef(time)));

                    // 🌟 遵照导师指示：数据来源和主题分类全部采用 StringField，不分词精确索引！
                    luceneDoc.add(new StringField("source", source != null ? source : "品玩", Field.Store.YES));
                    luceneDoc.add(new StringField("topic", topic != null ? topic : "综合", Field.Store.YES));

                    luceneDoc.add(new StringField("url", url != null ? url : "", Field.Store.YES));

                    // 以标题作为唯一标识更新，防止重复
                    writer.updateDocument(new Term("title", title), luceneDoc);
                    writer.commit();
                }
            } catch (Exception e) {
                System.err.println("⚠️ Lucene 写入失败: " + e.getMessage());
            }
        }
    }
}