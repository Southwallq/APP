package org.example;

import com.mongodb.client.MongoCollection;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ZgkejizxDualDbCrawler implements PageProcessor {

    private Site site = Site.me().setRetryTimes(3).setSleepTime(500).setTimeOut(10000)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

    @Override
    public void process(Page page) {
        System.out.println("👉 正在探测页面：" + page.getUrl().toString());

        boolean isArticlePage = page.getUrl().regex("https?://www\\.zgkejizx\\.com/.*\\d{4,}.*\\.html").match();

        if (isArticlePage) {
            // ======== 1. 提取标题 ========
            String title = page.getHtml().xpath("//h1/text()").get();
            if (title == null || title.trim().isEmpty()) {
                title = page.getHtml().xpath("//title/text()").get();
                if (title != null && title.contains("-")) title = title.split("-")[0].trim();
            }

            // ======== 2. 提取并清洗发布时间 (此部分逻辑正确，予以保留) ========
            String rawTime = page.getHtml().xpath("//div[@class='info']//text()").get();
            if (rawTime == null) {
                rawTime = page.getHtml().xpath("//div[contains(@class,'time')]//text()").get();
            }

            String finalTime = "未知时间";
            if (rawTime != null) {
                String timePattern = "(\\d{4}年\\d{1,2}月\\d{1,2}日)";
                List<String> timeMatches = us.codecraft.webmagic.selector.Selectors.regex(timePattern).selectList(rawTime);
                if (!timeMatches.isEmpty()) {
                    finalTime = timeMatches.get(0);
                } else if (!rawTime.trim().isEmpty()){
                    finalTime = rawTime.trim().substring(0, Math.min(rawTime.trim().length(), 25)); // 截断过长的来源信息
                }
            }

            // ======== 3. 提取正文 (采用更安全、更细化的清洗策略) ========
            List<String> rawContentList = page.getHtml().xpath("//div[contains(@class,'content') or contains(@class,'article')]//p//text()").all();
            if (rawContentList == null || rawContentList.isEmpty()) {
                rawContentList = page.getHtml().xpath("//div[contains(@class,'content') or contains(@class,'article')]//text()").all();
            }

            StringBuilder cleanContent = new StringBuilder();

            for (String line : rawContentList) {
                String tLine = line.trim();

                // ---【新的清洗逻辑】---

                // 1. 如果遇到明确的截断标志，就停止收集
                if (tLine.contains("相关阅读") || tLine.contains("延伸阅读") || tLine.contains("推荐文章")) {
                    break;
                }

                // 2. 过滤掉非常具体的、独立的垃圾信息行
                if (tLine.startsWith("分享到：") || tLine.startsWith("责任编辑：")) {
                    continue;
                }

                // 3. 过滤掉非常短的、无意义的行
                if (tLine.length() < 2) {
                    continue;
                }

                cleanContent.append(tLine).append("\n");
            }
            String finalContent = cleanContent.toString().trim();

            // ======== 4. 数据封装 ========
            if (title != null && !title.trim().isEmpty() && finalContent.length() > 20) {
                page.putField("title", title);
                page.putField("time", finalTime);
                page.putField("content", finalContent);
                page.putField("url", page.getUrl().toString());
            } else {
                // 如果到这里还是不满足条件，才判定为无效页面
                page.setSkip(true);
            }

        } else {
            // 列表页逻辑
            page.addTargetRequests(page.getHtml().links().regex("(https?://www\\.zgkejizx\\.com/.*\\.html)").all());
            page.setSkip(true);
        }
    }



    @Override
    public Site getSite() { return site; }

    // =========================================================
    // 优化的 Pipeline：使用 JDBC 工具类
    // =========================================================
    public static class DualDbPipeline implements Pipeline {
        private int count = 0;
        private final int limit = 3;
        private Spider spider;
        private Set<String> savedTitles = new HashSet<>();

        private Connection mysqlConn;
        private MongoCollection<Document> mongoColl;

        public DualDbPipeline() {
            try {
                // ✨ 这里通过调用 JDBC 工具类获取连接
                this.mysqlConn = JDBC.getMysqlConnection();
                this.mongoColl = JDBC.getMongoCollection("news_content");
                System.out.println("✅ 数据库工具类 JDBC 调用成功，双库已连接！");
            } catch (Exception e) {
                System.err.println("❌ 数据库初始化失败：" + e.getMessage());
            }
        }

        public void setSpider(Spider spider) { this.spider = spider; }

        @Override
        public synchronized void process(ResultItems resultItems, Task task) {
            if (count >= limit) return;
            String title = resultItems.get("title");
            if (title == null || savedTitles.contains(title)) return;
            savedTitles.add(title);

            try {
                // 1. 存入 MySQL
                String sql = "INSERT IGNORE INTO news_data (title, url, publish_time) VALUES (?, ?, ?)";
                try (PreparedStatement pstmt = mysqlConn.prepareStatement(sql)) {
                    pstmt.setString(1, title);
                    pstmt.setString(2, resultItems.get("url"));
                    pstmt.setString(3, resultItems.get("time"));
                    pstmt.executeUpdate();
                }

                // 2. 存入 MongoDB
                Document doc = new Document("title", title)
                        .append("url", resultItems.get("url"))
                        .append("content", resultItems.get("content"));
                mongoColl.insertOne(doc);

                count++;
                System.out.println("✅️ 已存入双库 (第 " + count + " 条)：" + title);

                if (count >= limit && spider != null) spider.stop();
            } catch (Exception e) {
                System.err.println("❌ 写入数据库异常：" + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        Spider spider = Spider.create(new ZgkejizxDualDbCrawler())
                .addUrl("http://www.zgkejizx.com/").thread(3);

        DualDbPipeline pipeline = new DualDbPipeline();
        pipeline.setSpider(spider);
        spider.addPipeline(pipeline).run();
    }
}
