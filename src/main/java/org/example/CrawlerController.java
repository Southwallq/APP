package org.example;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import us.codecraft.webmagic.Spider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/crawler")
public class CrawlerController {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();

    // 🌟 构造函数注入 DeepSeekService，用于分发给 Pipeline 里的 AI 核心
    private final DeepSeekService deepSeekService;

    public CrawlerController(DeepSeekService deepSeekService) {
        this.deepSeekService = deepSeekService;
    }

    @GetMapping("/start")
    public String startCrawler() {
        new Thread(() -> {
            System.out.println("🌐 收到品玩爬虫指令，开始获取初始链接池...");
            int targetCount = 100;
            int urlPoolLimit = 1000;
            int threadNum = 5;

            List<String> targetUrls = fetchUrlPool(urlPoolLimit);
            if (targetUrls.isEmpty()) {
                System.err.println("❌ 获取品玩链接失败");
                return;
            }

            System.out.println("🚀 品玩链接池就绪，开始爬取...");
            PingWestCrawler crawler = new PingWestCrawler();
            // 🌟 修复点：将注入的 deepSeekService 传给构造函数
            PingWestCrawler.MultiDbPipeline pipeline = new PingWestCrawler.MultiDbPipeline(targetCount, deepSeekService);

            Spider spider = Spider.create(crawler)
                    .addUrl(targetUrls.toArray(new String[0]))
                    .addPipeline(pipeline)
                    .thread(threadNum);

            pipeline.setSpider(spider);
            spider.run();
        }).start();

        return "🚀 品玩爬虫已在服务器后台启动，请查看服务器日志！";
    }

    /**
     * 🚀 启动极客公园爬虫
     */
    @GetMapping("/start-geekpark")
    public String startGeekParkCrawler() {
        new Thread(() -> {
            System.out.println("🌐 [极客公园] 收到指令，开始获取初始链接池...");
            int targetCount = 100;
            int urlPoolLimit = 1000;
            int threadNum = 5;

            List<String> targetUrls = fetchGeekParkUrlPool(urlPoolLimit);
            if (targetUrls.isEmpty()) {
                System.err.println("❌ 极客公园链接获取失败");
                return;
            }

            System.out.println("🚀 极客公园链接池就绪，开始爬取...");

            GeekParkCrawler crawler = new GeekParkCrawler();
            // 🌟 修复点：同样将注入的 deepSeekService 传给构造函数
            PingWestCrawler.MultiDbPipeline pipeline = new PingWestCrawler.MultiDbPipeline(targetCount, deepSeekService);

            Spider spider = Spider.create(crawler)
                    .addUrl(targetUrls.toArray(new String[0]))
                    .addPipeline(pipeline)
                    .thread(threadNum);

            pipeline.setSpider(spider);
            spider.run();
        }).start();

        return "🚀 极客公园爬虫已在后台启动，请查看服务器日志！";
    }

    /**
     * 品玩 API 翻页逻辑 + 增量查重早停机制
     */
    private List<String> fetchUrlPool(int limit) {
        List<String> urlList = new ArrayList<>();
        Set<String> uniqueUrls = new HashSet<>();
        String lastId = "";

        while (urlList.size() < limit) {
            try {
                Request request = new Request.Builder()
                        .url("https://www.pingwest.com/api/index_news_list?last_id=" + lastId)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .addHeader("Referer", "https://www.pingwest.com/")
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) break;
                    String json = response.body().string();
                    Pattern urlPattern = Pattern.compile("[\\\\/]+(a|w)[\\\\/]+(\\d{4,})");
                    Matcher matcher = urlPattern.matcher(json);

                    int countInPage = 0;
                    int duplicateCount = 0;
                    String currentLastArticleId = "";

                    while (matcher.find()) {
                        String type = matcher.group(1);
                        String articleId = matcher.group(2);
                        String fullUrl = "https://www.pingwest.com/" + type + "/" + articleId;

                        if (isUrlExistInDb(fullUrl)) {
                            duplicateCount++;
                            currentLastArticleId = articleId;
                            continue;
                        }

                        if (uniqueUrls.add(fullUrl)) {
                            urlList.add(fullUrl);
                            countInPage++;
                        }
                        currentLastArticleId = articleId;
                    }

                    int totalFound = countInPage + duplicateCount;

                    if (totalFound > 0 && duplicateCount == totalFound) {
                        System.out.println("🛑 [品玩] 发现本页 " + duplicateCount + " 条全为旧数据，已追平更新进度，触发早停！");
                        break;
                    }

                    if (totalFound == 0 || currentLastArticleId.isEmpty()) break;
                    lastId = currentLastArticleId;
                }
                System.out.println("📊 [品玩] 已收集新链接: " + urlList.size() + " / " + limit);
                Thread.sleep(1000);
            } catch (Exception e) {
                break;
            }
        }
        return urlList;
    }

    /**
     * 极客公园 API 翻页逻辑：page=1, 2, 3... + 早停机制 + 异常雷达
     */
    private List<String> fetchGeekParkUrlPool(int limit) {
        List<String> urlList = new ArrayList<>();
        Set<String> uniqueUrls = new HashSet<>();
        int page = 1;

        while (urlList.size() < limit) {
            try {
                String apiUrl = "https://mainssl.geekpark.net/api/v2?page=" + page;

                // 🌟 加固请求头，伪装得更像真实浏览器，并明确告诉服务器要 JSON
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .addHeader("Referer", "https://www.geekpark.net/")
                        .addHeader("Accept", "application/json, text/plain, */*")
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    // 🌟 雷达1号：如果状态码不是 200 OK，立刻打印出错误码！
                    if (!response.isSuccessful()) {
                        System.err.println("❌ 极客公园 API 请求失败, HTTP状态码: " + response.code());
                        break;
                    }
                    if (response.body() == null) break;

                    String json = response.body().string();

                    // 🌟 雷达2号：增强版正则！同时兼容 "/news/314159" 和 `"id": 314159` 这两种可能的数据格式
                    Pattern urlPattern = Pattern.compile("(\"id\"\\s*:\\s*\"?|/news/)(\\d{5,})");
                    Matcher matcher = urlPattern.matcher(json);

                    int countInPage = 0;
                    int duplicateCount = 0;

                    while (matcher.find()) {
                        countInPage++;
                        String articleId = matcher.group(2); // 提取第二组括号里的纯数字 ID
                        String fullUrl = "https://www.geekpark.net/news/" + articleId;

                        if (isUrlExistInDb(fullUrl)) {
                            duplicateCount++;
                            continue;
                        }

                        if (uniqueUrls.add(fullUrl)) {
                            urlList.add(fullUrl);
                        }
                    }

                    // 🌟 终极雷达：如果第一页连一个 ID 都没匹配到，直接把极客公园到底返回了什么鬼东西给打印出来！
                    if (page == 1 && countInPage == 0) {
                        System.err.println("⚠️ 极客公园正则未命中！API 真实返回内容片段: " + json.substring(0, Math.min(json.length(), 300)));
                        break;
                    }

                    if (countInPage > 0 && duplicateCount == countInPage) {
                        System.out.println("🛑 [极客公园] 发现本页 " + duplicateCount + " 条全为旧数据，已追平更新进度，触发早停机制！");
                        break;
                    }
                    if (countInPage == 0) break;
                }
                page++;
                Thread.sleep(1500); // 稍微放慢一点点频率
            } catch (Exception e) {
                System.err.println("❌ 极客公园抓取异常: " + e.getMessage());
                break;
            }
        }
        return urlList;
    }

    /**
     * 🔍 查重雷达：去 MySQL 问一下这个 URL 存过没有？
     */
    private boolean isUrlExistInDb(String url) {
        String sql = "SELECT 1 FROM news_data WHERE url = ? LIMIT 1";
        try (Connection conn = JDBC.getMysqlConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, url);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            System.err.println("数据库查重失败，默认放行: " + e.getMessage());
            return false;
        }
    }

    /**
     * ⏰ 定时自动化爬虫任务 (每天凌晨 2点 和 下午 14点)
     */
    @Scheduled(cron = "0 0 2,14 * * ?")
    public void autoRunCrawlers() {
        System.out.println("⏰ 定时任务触发：开始自动执行全网新闻抓取...");
        startGeekParkCrawler();
        startCrawler();
        System.out.println("✅ 所有爬虫指令已发送至后台线程！");
    }
}