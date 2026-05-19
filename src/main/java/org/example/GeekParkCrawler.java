package org.example;

import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极客公园爬虫解析器 (超强去牛皮癣版)
 */
public class GeekParkCrawler implements PageProcessor {

    private Site site = Site.me()
            .setCharset("UTF-8")
            .setRetryTimes(3)
            .setSleepTime(1500) // 防封停顿
            .setTimeOut(15000)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

    @Override
    public void process(Page page) {
        String url = page.getUrl().toString();
        String title = page.getHtml().xpath("//title/text()").get();

        if (title != null) {
            title = title.replace("- 极客公园", "").replace("_极客公园", "").trim();
        }

        // 时间校验 (只爬 2026 年的数据)
        String publishTime = extractPerfectPublishTime(page);
        if (publishTime == null || !publishTime.startsWith("2026")) {
            page.setSkip(true);
            return;
        }

        // 🌟 正文清洗：极客公园专属“牛皮癣”强力过滤网
        List<String> allParagraphs = page.getHtml().xpath("//body//p//text()").all();
        StringBuilder cleanContent = new StringBuilder();

        String[] filterKeywords = {
                // 通用垃圾话
                "扫码查看", "每日要闻", "关注公众号", "点击查看", "阅读原文", "广告",
                "这家伙很懒", "请「」后评论", "请「登录」后评论",

                // 🌟 极客公园底部声明专用过滤词 (提取核心特征，防止空格干扰)
                "用极客视角",
                "追踪你不可错过",
                "新鲜、有趣的硬件产品",
                "第一时间为你呈现",
                "聊科技，谈商业",
                "联系电话：8610",
                "联系邮箱：",
                "公司地址：北京市朝阳区",
                "正东集团院内",
                "751 D·Park"
        };

        for (String p : allParagraphs) {
            String trimP = p.trim();
            if (trimP.length() < 5) continue;

            boolean isAd = false;
            for (String kw : filterKeywords) {
                if (trimP.contains(kw)) {
                    isAd = true;
                    break;
                }
            }
            // 如果不是广告和废话，才拼接到正文里
            if (!isAd) cleanContent.append(trimP).append("\n");
        }

        // 图片提取
        List<String> articleImageUrls = new ArrayList<>();
        List<String> containerImgs = page.getHtml().xpath("//article//img/@src | //div[contains(@class,'article-content')]//img/@src | //div[@id='article-body']//img/@src").all();

        for (String imgUrl : containerImgs) {
            if (imgUrl != null && imgUrl.length() > 20) {
                if (imgUrl.startsWith("//")) imgUrl = "https:" + imgUrl;
                if (!imgUrl.contains("logo") && !imgUrl.contains("avatar") && !imgUrl.contains("qr")) {
                    articleImageUrls.add(imgUrl);
                }
            }
        }

        // 传给 Pipeline 处理
        if (title != null && !title.isEmpty() && cleanContent.length() > 50) {
            page.putField("title", title);
            page.putField("time", publishTime);
            page.putField("content", cleanContent.toString().trim());
            page.putField("url", url);
            page.putField("images", articleImageUrls);
            page.putField("source", "极客公园");
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
}