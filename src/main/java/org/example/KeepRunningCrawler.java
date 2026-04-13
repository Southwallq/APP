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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeepRunningCrawler implements PageProcessor {

    private Site site = Site.me()
            .setCharset("UTF-8")
            .setRetryTimes(3)
            .setSleepTime(1500) // 稍微放慢一点，避免给品玩压力太大，也防止链接重复太快
            .setTimeOut(15000)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0.0.0 Safari/537.36");

    @Override
    public void process(Page page) {
        String url = page.getUrl().toString();

        boolean isArticlePage = url.matches("https://www.pingwest.com/a/\\d+");

        if (isArticlePage) {
            System.out.println("👉 正在处理文章：" + url);

            // 1. 提取标题
            String title = page.getHtml().xpath("//meta[@property='og:title']/@content").get();
            if (title != null && title.contains("-品玩")) {
                title = title.replace("-品玩", "").trim();
            }

            // 2. 提取发布时间
            String publishTime = extractPerfectPublishTime(page);
            boolean is2026News = publishTime != null && publishTime.startsWith("2026");

            if (!is2026News) {
                System.out.println("   ⏭️  跳过：非2026年新闻 (" + publishTime + ")");
                page.setSkip(true);
                // 即使跳过，也别忘了把首页加回去，保证链接不断
                page.addTargetRequest("https://www.pingwest.com/");
                return;
            }

            // 3. 提取正文 + 数据清洗
            List<String> allParagraphs = page.getHtml().xpath("//body//p//text()").all();
            StringBuilder cleanContent = new StringBuilder();
            String[] filterKeywords = {
                    "扫码查看", "每日要闻", "请「」后评论", "关注公众号",
                    "点击查看", "阅读原文", "广告", "推广", "福利"
            };
            for (String p : allParagraphs) {
                String trimP = p.trim();
                boolean isUseful = true;
                if (trimP.length() < 5) isUseful = false;
                else {
                    for (String keyword : filterKeywords) {
                        if (trimP.contains(keyword)) { isUseful = false; break; }
                    }
                }
                if (isUseful) cleanContent.append(trimP).append("\n");
            }
            String finalContent = cleanContent.toString().trim();

            // 4. 精准提取正文图片
            List<String> articleImageUrls = new ArrayList<>();
            String[] articleContainerXPaths = {
                    "//article", "//div[contains(@class,'article-content')]",
                    "//div[contains(@class,'article-body')]", "//div[contains(@class,'content')]"
            };
            for (String containerXPath : articleContainerXPaths) {
                List<String> containerImgs = page.getHtml().xpath(containerXPath + "//img/@src").all();
                if (containerImgs != null && !containerImgs.isEmpty()) {
                    for (String imgUrl : containerImgs) {
                        if (imgUrl != null && imgUrl.length() > 20) {
                            if (imgUrl.startsWith("//")) imgUrl = "https:" + imgUrl;
                            if ((imgUrl.contains("cdn.pingwest.com") || imgUrl.contains("pingwest.com"))
                                    && !imgUrl.contains("qrcode") && !imgUrl.contains("icon")
                                    && !imgUrl.contains("logo") && !imgUrl.contains("avatar")) {
                                articleImageUrls.add(imgUrl);
                            }
                        }
                    }
                    if (!articleImageUrls.isEmpty()) break;
                }
            }

            // ======================================
            // ✅【核心修复】无论如何，把首页加回队列，保证链接源源不断
            // ======================================
            page.addTargetRequest("https://www.pingwest.com/");

            // 封装数据
            if (title != null && !title.trim().isEmpty()) {
                page.putField("title", title);
                page.putField("time", publishTime);
                page.putField("content", finalContent);
                page.putField("url", url);
                page.putField("images", articleImageUrls);
            } else {
                page.setSkip(true);
            }

        } else {
            // ======================================
            // ✅【核心修复】列表页：疯狂提取链接，并把自己也加回队列
            // ======================================
            System.out.println("🔄 正在刷新列表页获取新链接...");
            List<String> links = page.getHtml().links().regex("https://www.pingwest.com/a/\\d+").all();
            System.out.println("🔍 列表页发现链接：" + links.size() + " 个");
            page.addTargetRequests(links);

            // 关键：把列表页自己也加回队列，循环刷新
            page.addTargetRequest("https://www.pingwest.com/");
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
    public Site getSite() {
        return site;
    }

    // ===================== Pipeline 保持不变 =====================
    public static class DualDbPipeline implements Pipeline {
        private int count = 0;
        private final int limit = 100;
        private Spider spider;
        private Set<String> savedTitles = new HashSet<>();

        private Connection mysqlConn;
        private MongoCollection<Document> mongoColl;

        public DualDbPipeline() {
            try {
                this.mysqlConn = JDBC.getMysqlConnection();
                this.mongoColl = JDBC.getMongoCollection("news_content");
                System.out.println("✅ 双数据库连接成功！目标爬取：" + limit + " 条2026年科技新闻\n");
            } catch (Exception e) {
                System.err.println("❌ 数据库初始化失败：" + e.getMessage());
                e.printStackTrace();
            }
        }

        public void setSpider(Spider spider) {
            this.spider = spider;
        }

        @Override
        public synchronized void process(ResultItems resultItems, Task task) {
            if (count >= limit) return;
            String title = resultItems.get("title");
            if (title == null || savedTitles.contains(title)) return;
            savedTitles.add(title);

            try {
                String timeToWrite = resultItems.get("time");
                String sql = "INSERT IGNORE INTO news_data (title, url, publish_time) VALUES (?, ?, ?)";
                try (PreparedStatement pstmt = mysqlConn.prepareStatement(sql)) {
                    pstmt.setString(1, title);
                    pstmt.setString(2, resultItems.get("url"));
                    pstmt.setString(3, timeToWrite);
                    pstmt.executeUpdate();
                }

                List<String> images = resultItems.get("images");
                Document doc = new Document("title", title)
                        .append("url", resultItems.get("url"))
                        .append("publish_time", timeToWrite)
                        .append("content", resultItems.get("content"))
                        .append("image_count", images != null ? images.size() : 0)
                        .append("images", images != null ? images : new ArrayList<>());
                mongoColl.insertOne(doc);

                count++;
                System.out.println("\n🎉 【成功 " + count + "/" + limit + "】" + title);
                System.out.println("   🕐 时间：" + timeToWrite + " | 🖼️ 图片：" + (images != null ? images.size() : 0) + " 张\n");

                if (count >= limit && spider != null) {
                    System.out.println("✅✅✅ 恭喜！已成功爬满 " + limit + " 条2026年科技新闻！爬虫停止！");
                    spider.stop();
                }
            } catch (Exception e) {
                System.err.println("❌ 保存失败：" + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        Spider spider = Spider.create(new KeepRunningCrawler())
                .addUrl("https://www.pingwest.com/")
                .thread(1); // 单线程更稳定，防止重复混乱

        DualDbPipeline pipeline = new DualDbPipeline();
        pipeline.setSpider(spider);
        spider.addPipeline(pipeline).run();
    }
}