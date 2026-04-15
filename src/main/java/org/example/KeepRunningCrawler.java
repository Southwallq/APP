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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeepRunningCrawler implements PageProcessor {

    private Site site = Site.me()
            .setCharset("UTF-8")
            .setRetryTimes(3)
            .setSleepTime(1500)
            .setTimeOut(15000)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0.0.0 Safari/537.36");

    @Override
    public void process(Page page) {
        String url = page.getUrl().toString();
        System.out.println("👉 正在处理文章：" + url);

        String title =  page.getHtml().xpath("//meta[@property='og:title']/@content").get();
        if (title != null && title.contains("-品玩")) {
            title = title.replace("-品玩", "").trim();
        }

        String publishTime = extractPerfectPublishTime(page);
        boolean is2026News = publishTime != null && publishTime.startsWith("2026");

        if (!is2026News) {
            System.out.println("   ⏭️  跳过：非2026年新闻 (" + publishTime + ")");
            page.setSkip(true);
            return;
        }

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

        if (title != null && !title.trim().isEmpty()) {
            page.putField("title", title);
            page.putField("time", publishTime);
            page.putField("content", finalContent);
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
    public Site getSite() {
        return site;
    }

    // ======================= 你的 DualDbPipeline 100% 完全原样 =======================
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

    // ======================= 核心优化：先爬主页 → 再双向精准查找 =======================
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder().build();
    private static final int TARGET_COUNT = 100;

    public static void main(String[] args) {
        System.out.println("🚀 第一步：从品玩主页获取初始链接...");
        List<String> allUrls = new CopyOnWriteArrayList<>();
        Set<String> urlSet = ConcurrentHashMap.newKeySet();
        List<Long> allIds = new ArrayList<>();

        // 1. 先爬主页
        crawlHomePage(allUrls, urlSet, allIds);

        if (allUrls.size() >= TARGET_COUNT) {
            System.out.println("✅ 主页直接获取到足够链接，共 " + allUrls.size() + " 个");
        } else {
            System.out.println("📌 主页只获取到 " + allUrls.size() + " 个链接，启动双线程API查找...");

            // 2. 从主页链接中找到最大和最小ID
            long maxId = allIds.stream().mapToLong(Long::longValue).max().orElse(312716);
            long minId = allIds.stream().mapToLong(Long::longValue).min().orElse(312716);
            System.out.println("   主页最大ID: " + maxId + "，最小ID: " + minId);

            // 3. 启动双线程精准查找（不跳步）
            crawlDualThread(maxId + 1, minId, allUrls, urlSet, TARGET_COUNT);
        }

        // 截取目标数量
        if (allUrls.size() > TARGET_COUNT) {
            allUrls = allUrls.subList(0, TARGET_COUNT);
        }

        System.out.println("✅ 准备完成，共获取 " + allUrls.size() + " 个链接，开始爬取...\n");

        Spider spider = Spider.create(new KeepRunningCrawler())
                .addUrl(allUrls.toArray(new String[0]))
                .thread(1);

        DualDbPipeline pipeline = new DualDbPipeline();
        pipeline.setSpider(spider);
        spider.addPipeline(pipeline).run();
    }

    /**
     * 爬取主页获取初始链接
     */
    private static void crawlHomePage(List<String> urls, Set<String> urlSet, List<Long> ids) {
        try {
            String homeUrl = "https://www.pingwest.com/";
            Request request = new Request.Builder()
                    .url(homeUrl)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Cache-Control", "no-cache")
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                String html = response.body().string();

                // 提取链接和ID
                Pattern urlPattern = Pattern.compile("//www.pingwest.com/(a|w)/(\\d+)");
                Matcher matcher = urlPattern.matcher(html);
                while (matcher.find()) {
                    String fullUrl = "https:" + matcher.group(1) + "/" + matcher.group(2);
                    String fullUrl2 = "https://www.pingwest.com/" + matcher.group(1) + "/" + matcher.group(2);
                    if (!urlSet.contains(fullUrl2)) {
                        urlSet.add(fullUrl2);
                        urls.add(fullUrl2);
                        ids.add(Long.parseLong(matcher.group(2)));
                    }
                }
                System.out.println("   主页获取到 " + urls.size() + " 个链接");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 双线程精准查找（不跳步）
     */
    private static void crawlDualThread(long startAscId, long startDescId,
                                        List<String> urls, Set<String> urlSet, int targetCount) {
        // 线程1：从最大ID+1开始递增，找更新的文章
        Thread ascThread = new Thread(() -> {
            crawlDirection(startAscId, true, urls, urlSet, targetCount, "【更新线程】");
        }, "Asc-Thread");

        // 线程2：从最小ID开始递减，找历史文章
        Thread descThread = new Thread(() -> {
            crawlDirection(startDescId, false, urls, urlSet, targetCount, "【历史线程】");
        }, "Desc-Thread");

        ascThread.start();
        descThread.start();

        try {
            while (urls.size() < targetCount && (ascThread.isAlive() || descThread.isAlive())) {
                Thread.sleep(100);
            }
            ascThread.interrupt();
            descThread.interrupt();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 单向精准查找（不跳步）
     */
    private static void crawlDirection(long startId, boolean isAsc,
                                       List<String> urls, Set<String> urlSet,
                                       int targetCount, String threadName) {
        long currentId = startId;
        OkHttpClient client = new OkHttpClient.Builder().build();

        while (!Thread.currentThread().isInterrupted() && urls.size() < targetCount) {
            try {
                System.out.println(threadName + " 正在加载API last_id=" + currentId);
                String api = "https://www.pingwest.com/api/index_news_list?last_id=" + currentId;
                Request request = new Request.Builder()
                        .url(api)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .addHeader("Cache-Control", "no-cache")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        currentId = isAsc ? currentId + 1 : currentId - 1;
                        continue;
                    }
                    String json = response.body().string();

                    // 提取链接
                    Pattern urlPattern = Pattern.compile("//www.pingwest.com/(a|w)/\\d+");
                    Matcher urlMatcher = urlPattern.matcher(json);
                    boolean hasNew = false;
                    while (urlMatcher.find()) {
                        String fullUrl = "https:" + urlMatcher.group();
                        if (!urlSet.contains(fullUrl)) {
                            urlSet.add(fullUrl);
                            urls.add(fullUrl);
                            hasNew = true;
                        }
                    }

                    if (hasNew) {
                        System.out.println(threadName + " 已获取 " + urls.size() + "/" + targetCount + " 个链接");

                        // 找到当前页的最小/最大ID
                        List<Long> pageIds = new ArrayList<>();
                        Pattern idPattern = Pattern.compile("<article id=\"(\\d+)\"");
                        Matcher idMatcher = idPattern.matcher(json);
                        while (idMatcher.find()) {
                            pageIds.add(Long.parseLong(idMatcher.group(1)));
                        }

                        if (!pageIds.isEmpty()) {
                            if (isAsc) {
                                // 递增方向：找最大ID + 1
                                long maxId = pageIds.stream().mapToLong(Long::longValue).max().getAsLong();
                                currentId = maxId + 1;
                            } else {
                                // 递减方向：找最小ID
                                long minId = pageIds.stream().mapToLong(Long::longValue).min().getAsLong();
                                currentId = minId;
                            }
                        } else {
                            currentId = isAsc ? currentId + 1 : currentId - 1;
                        }
                    } else {
                        // 无新链接，继续+1或-1
                        currentId = isAsc ? currentId + 1 : currentId - 1;
                    }

                    Thread.sleep(300);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    break;
                }
                e.printStackTrace();
                currentId = isAsc ? currentId + 1 : currentId - 1;
            }
        }
    }
}