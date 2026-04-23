package org.example;

import com.mongodb.client.MongoCollection;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bson.Document;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.pipeline.Pipeline;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini Enterprise 优化版爬虫
 * 解决翻页中断、采集量不足及日期过滤问题
 */
public class PingWestCrawler implements PageProcessor {

    // ======================= 配置区域 =======================
    private static final int TARGET_COUNT = 100; // 最终入库目标
    private static final int URL_POOL_LIMIT = 150; // 预取链接池大小（建议设为目标的5-6倍，应对日期过滤）
    private static final String API_BASE = "https://www.pingwest.com/api/index_news_list?last_id=";

    private Site site = Site.me()
            .setCharset("UTF-8")
            .setRetryTimes(3)
            .setSleepTime(1200)
            .setTimeOut(15000)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");

    @Override
    public void process(Page page) {
        String url = page.getUrl().toString();

        // 1. 提取标题并清理
        String title = page.getHtml().xpath("//meta[@property='og:title']/@content").get();
        if (title != null && title.contains("-品玩")) {
            title = title.replace("-品玩", "").trim();
        }

        // 2. 严格日期校验 (2026年)
        String publishTime = extractPerfectPublishTime(page);
        boolean is2026News = publishTime != null && publishTime.startsWith("2026");

        if (!is2026News) {
            page.setSkip(true); // 核心：非2026年数据不进入 Pipeline
            return;
        }

        // 3. 正文提取与清洗
        List<String> allParagraphs = page.getHtml().xpath("//body//p//text()").all();
        StringBuilder cleanContent = new StringBuilder();
        String[] filterKeywords = {"扫码查看", "每日要闻", "关注公众号", "点击查看", "阅读原文", "广告"};

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

        if (title != null && !title.isEmpty()) {
            page.putField("title", title);
            page.putField("time", publishTime);
            page.putField("content", cleanContent.toString().trim());
            page.putField("url", url);
            page.putField("images", articleImageUrls);
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
        return "1990-01-01";
    }

    @Override
    public Site getSite() { return site; }

    // ======================= 双库持久化管道 =======================
    public static class DualDbPipeline implements Pipeline {
        private int count = 0;
        private Spider spider;
        private Set<String> savedTitles = ConcurrentHashMap.newKeySet();
        private Connection mysqlConn;
        private MongoCollection<Document> mongoColl;

        public DualDbPipeline() {
            try {
                this.mysqlConn = JDBC.getMysqlConnection();
                this.mongoColl = JDBC.getMongoCollection("news_content");
                System.out.println("✅ 数据库准备就绪，开始筛选 2026 年科技新闻...");
            } catch (Exception e) {
                System.err.println("❌ 数据库连接异常: " + e.getMessage());
            }
        }

        public void setSpider(Spider spider) { this.spider = spider; }

        @Override
        public synchronized void process(ResultItems resultItems, Task task) {
            if (count >= TARGET_COUNT) return;

            String title = resultItems.get("title");
            if (title == null || savedTitles.contains(title)) return;
            savedTitles.add(title);

            try {
                String time = resultItems.get("time");
                // 1. MySQL 存索引
                String sql = "INSERT IGNORE INTO news_data (title, url, publish_time) VALUES (?, ?, ?)";
                try (PreparedStatement pstmt = mysqlConn.prepareStatement(sql)) {
                    pstmt.setString(1, title);
                    pstmt.setString(2, resultItems.get("url"));
                    pstmt.setString(3, time);
                    pstmt.executeUpdate();
                }

                // 2. MongoDB 存正文
                List<String> images = resultItems.get("images");
                Document doc = new Document("title", title)
                        .append("url", resultItems.get("url"))
                        .append("publish_time", time)
                        .append("content", resultItems.get("content"))
                        .append("images", images != null ? images : new ArrayList<>());
                mongoColl.insertOne(doc);

                count++;
                System.out.println("🔥 [" + count + "/" + TARGET_COUNT + "] 已入库: " + title + " (" + time + ")");

                if (count >= TARGET_COUNT && spider != null) {
                    System.out.println("\n✨ 目标达成！成功爬取 100 条 2026 年新闻，正在停止...");
                    spider.stop();
                }
            } catch (Exception e) {
                System.err.println("❌ 入库失败: " + e.getMessage());
            }
        }
    }

    // ======================= API 采集核心逻辑 =======================
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();

    public static void main(String[] args) {
        System.out.println("🌐 正在通过 API 初始化链接池...");

        List<String> targetUrls = new ArrayList<>();
        Set<String> urlSet = new HashSet<>();
        String lastId = "";
        int apiRetries = 0;

        // 持续翻页直到拿到足够多的原始链接
        while (targetUrls.size() < URL_POOL_LIMIT && apiRetries < 60) {
            apiRetries++;
            // 首次请求 lastId 为空，后续带有正确的 nextId
            String apiResponse = fetchApi(API_BASE + lastId);

            if (apiResponse == null || apiResponse.length() < 100) {
                System.out.println("⚠️ API 返回异常或为空，停止翻页。");
                break;
            }

            List<String> pageUrls = extractUrlsFromJson(apiResponse);
            // 传入 pageUrls 作为兜底提取策略
            String nextId = extractLastArticleId(apiResponse, pageUrls);

            int newCount = 0;
            for (String url : pageUrls) {
                if (urlSet.add(url)) {
                    targetUrls.add(url);
                    newCount++;
                }
            }

            System.out.println("📊 API 翻页进度: 本页新增 " + newCount + " 个链接 | 累计: " + targetUrls.size() + " | 下一页 ID: " + nextId);

            // 如果没拿到 nextId、nextId 没变，或者本页全都是重复的旧链接，则证明到底了
            if (nextId == null || nextId.equals(lastId) || newCount == 0) {
                System.out.println("🏁 无法获取下一页 ID 或无新链接，停止 API 获取。");
                break;
            }
            lastId = nextId;

            // 稍微延长间隔防止 API 触发反爬
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }

        if (targetUrls.isEmpty()) {
            System.err.println("❌ 未能获取到任何 URL，请检查网络或 API 接口。");
            return;
        }

        System.out.println("🚀 链接池准备完毕，共获取 " + targetUrls.size() + " 个链接。开始深度爬取...");

        // 启动 WebMagic 详情爬虫
        Spider spider = Spider.create(new PingWestCrawler())
                .addUrl(targetUrls.toArray(new String[0]))
                .thread(5);

        DualDbPipeline pipeline = new DualDbPipeline();
        pipeline.setSpider(spider);
        spider.addPipeline(pipeline).run();
    }

    private static String fetchApi(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("Referer", "https://www.pingwest.com/")
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .build();
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                return response.isSuccessful() && response.body() != null ? response.body().string() : null;
            }
        } catch (Exception e) { return null; }
    }

    private static List<String> extractUrlsFromJson(String json) {
        List<String> urls = new ArrayList<>();
        // 兼容 JSON 中可能存在的斜杠转义 (如匹配 \/a\/12345 或 /a/12345)
        // \d{4,} 确保匹配的是真正的文章 ID（至少 4 位数）
        Pattern pattern = Pattern.compile("[\\\\/]+(a|w)[\\\\/]+(\\d{4,})");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            String type = matcher.group(1); // "a" 或 "w"
            String id = matcher.group(2);   // 文章纯数字 ID
            urls.add("https://www.pingwest.com/" + type + "/" + id);
        }
        return urls;
    }

    private static String extractLastArticleId(String json, List<String> pageUrls) {
        String lastId = null;

        // 策略 1: 尝试匹配 JSON 根节点的 last_id 字段（部分 API 会直接在此返回）
        Matcher m1 = Pattern.compile("\"last_id\"\\s*:\\s*\"?(\\d+)\"?").matcher(json);
        if (m1.find()) return m1.group(1);

        // 策略 2: 匹配 HTML 属性 id 或 data-id，核心改变：使用 \\\\? 兼容转义的双引号（\"）
        Pattern p2 = Pattern.compile("(?:id|data-id|data-app-id)=\\\\?\"(\\d{4,})\\\\?\"");
        Matcher m2 = p2.matcher(json);
        while (m2.find()) {
            lastId = m2.group(1); // 循环到最后，自然获取当前页的最后一个 ID
        }
        if (lastId != null) return lastId;

        // 策略 3 (终极兜底): 如果 HTML 结构变化导致正则全军覆没，直接从刚刚提取到的最后一个 URL 中截取数字 ID！
        if (pageUrls != null && !pageUrls.isEmpty()) {
            String lastUrl = pageUrls.get(pageUrls.size() - 1);
            Matcher m3 = Pattern.compile("/(\\d+)$").matcher(lastUrl);
            if (m3.find()) return m3.group(1);
        }

        return null;
    }
}