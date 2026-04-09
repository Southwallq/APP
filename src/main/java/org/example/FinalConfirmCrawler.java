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

public class FinalConfirmCrawler implements PageProcessor {

    private Site site = Site.me()
            .setCharset("UTF-8")
            .setRetryTimes(2)
            .setSleepTime(1200)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0.0.0 Safari/537.36");

    @Override
    public void process(Page page) {
        String url = page.getUrl().toString();
        System.out.println("👉 正在爬取科技新闻：" + url);

        boolean isArticlePage = url.matches("https://www.pingwest.com/a/\\d+");

        if (isArticlePage) {
            // 1. 提取标题
            String title = page.getHtml().xpath("//meta[@property='og:title']/@content").get();
            if (title != null && title.contains("-品玩")) {
                title = title.replace("-品玩", "").trim();
            }

            // 2. 多策略提取发布时间
            String publishTime = extractPublishTime(page);

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
                if (trimP.length() < 5) {
                    isUseful = false;
                } else {
                    for (String keyword : filterKeywords) {
                        if (trimP.contains(keyword)) {
                            isUseful = false;
                            break;
                        }
                    }
                }
                if (isUseful) {
                    cleanContent.append(trimP).append("\n");
                }
            }
            String finalContent = cleanContent.toString().trim();

            // 4. 提取图片URL
            List<String> imageUrls = new ArrayList<>();
            List<String> rawImgUrls = page.getHtml().xpath("//body//img/@src").all();

            for (String imgUrl : rawImgUrls) {
                if (imgUrl != null && imgUrl.length() > 10) {
                    if (imgUrl.startsWith("//")) {
                        imgUrl = "https:" + imgUrl;
                    }
                    if (imgUrl.contains("cdn.pingwest.com") || imgUrl.contains("pingwest.com")) {
                        imageUrls.add(imgUrl);
                    }
                }
            }

            // 打印日志
            System.out.println("📌 提取成功：");
            System.out.println("   标题：" + title);
            System.out.println("   时间：" + publishTime);
            System.out.println("   正文长度：" + finalContent.length() + " 字符");
            System.out.println("   发现图片：" + imageUrls.size() + " 张");

            if (title != null && !title.trim().isEmpty()) {
                page.putField("title", title);
                page.putField("time", publishTime); // 封装时间
                page.putField("content", finalContent);
                page.putField("url", url);
                page.putField("images", imageUrls);
            } else {
                page.setSkip(true);
            }

        } else {
            List<String> links = page.getHtml()
                    .links()
                    .regex("https://www.pingwest.com/a/\\d+")
                    .all();
            System.out.println("🔍 发现科技新闻链接：" + links.size() + " 个");
            page.addTargetRequests(links);
            page.setSkip(true);
        }
    }

    // 多策略提取发布时间
    private String extractPublishTime(Page page) {
        // 策略1：从图片URL中提取日期（最可靠）
        List<String> imgUrls = page.getHtml().xpath("//body//img/@src").all();
        for (String imgUrl : imgUrls) {
            if (imgUrl != null && imgUrl.contains("cdn.pingwest.com")) {
                Pattern pattern = Pattern.compile("(\\d{4})/(\\d{2})/(\\d{2})");
                Matcher matcher = pattern.matcher(imgUrl);
                if (matcher.find()) {
                    return matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
                }
            }
        }

        // 策略2：从正文中提取“发布于 X月X日”
        List<String> allText = page.getHtml().xpath("//body//text()").all();
        for (String text : allText) {
            if (text != null && text.contains("发布于")) {
                Pattern pattern = Pattern.compile("发布于.*?(\\d+)[年/](sslocal://flow/file_open?url=%5C%5Cd%2B&flow_extra=eyJsaW5rX3R5cGUiOiJjb2RlX2ludGVycHJldGVyIn0=)[月/](sslocal://flow/file_open?url=%5C%5Cd%2B&flow_extra=eyJsaW5rX3R5cGUiOiJjb2RlX2ludGVycHJldGVyIn0=)");
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String year = matcher.group(1).length() == 2 ? "20" + matcher.group(1) : matcher.group(1);
                    String month = matcher.group(2).length() == 1 ? "0" + matcher.group(2) : matcher.group(2);
                    String day = matcher.group(3).length() == 1 ? "0" + matcher.group(3) : matcher.group(3);
                    return year + "-" + month + "-" + day;
                }
                Pattern simplePattern = Pattern.compile("发布于.*?(\\d+)月(\\d+)日");
                Matcher simpleMatcher = simplePattern.matcher(text);
                if (simpleMatcher.find()) {
                    String month = simpleMatcher.group(1).length() == 1 ? "0" + simpleMatcher.group(1) : simpleMatcher.group(1);
                    String day = simpleMatcher.group(2).length() == 1 ? "0" + simpleMatcher.group(2) : simpleMatcher.group(2);
                    return "2026-" + month + "-" + day;
                }
            }
        }

        // 策略3：系统当前时间（兜底）
        return java.time.LocalDate.now().toString();
    }

    @Override
    public Site getSite() {
        return site;
    }

    // ===================== 【确认版】双库存储 Pipeline =====================
    public static class DualDbPipeline implements Pipeline {
        private int count = 0;
        private final int limit = 3;
        private Spider spider;
        private Set<String> savedTitles = new HashSet<>();

        private Connection mysqlConn;
        private MongoCollection<Document> mongoColl;

        public DualDbPipeline() {
            try {
                this.mysqlConn = JDBC.getMysqlConnection();
                this.mongoColl = JDBC.getMongoCollection("news_content");
                System.out.println("✅ 双数据库连接成功！");
            } catch (Exception e) {
                System.err.println("❌ 数据库初始化失败：" + e.getMessage());
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
                // ======================================
                // ✅ 【高亮确认】写入 MySQL（包含发布时间！）
                // ======================================
                String sql = "INSERT IGNORE INTO news_data (title, url, publish_time) VALUES (?, ?, ?)";
                try (PreparedStatement pstmt = mysqlConn.prepareStatement(sql)) {
                    pstmt.setString(1, title);                                    // 第1个参数：标题
                    pstmt.setString(2, resultItems.get("url"));                  // 第2个参数：URL
                    pstmt.setString(3, resultItems.get("time"));                 // 第3个参数：发布时间
                    pstmt.executeUpdate();
                    System.out.println("   💾 MySQL写入成功（含时间）");
                }

                // ======================================
                // 写入 MongoDB（完整信息）
                // ======================================
                List<String> images = resultItems.get("images");
                Document doc = new Document("title", title)
                        .append("url", resultItems.get("url"))
                        .append("publish_time", resultItems.get("time"))
                        .append("content", resultItems.get("content"))
                        .append("image_count", images != null ? images.size() : 0)
                        .append("images", images != null ? images : new ArrayList<>());

                mongoColl.insertOne(doc);
                System.out.println("   💾 MongoDB写入成功（含图片）");

                count++;
                System.out.println("🎉 【全部完成】第 " + count + " 条：" + title);
                System.out.println("   🕐 发布时间：" + resultItems.get("time"));
                System.out.println("   🖼️  图片数量：" + (images != null ? images.size() : 0) + " 张\n");

                if (count >= limit && spider != null) {
                    System.out.println("✅ 已爬满 " + limit + " 条科技新闻，爬虫自动停止！");
                    spider.stop();
                }
            } catch (Exception e) {
                System.err.println("❌ 保存数据失败：" + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        Spider spider = Spider.create(new FinalConfirmCrawler())
                .addUrl("https://www.pingwest.com/")
                .thread(1);

        DualDbPipeline pipeline = new DualDbPipeline();
        pipeline.setSpider(spider);
        spider.addPipeline(pipeline).run();
    }
}