package org.example;

import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.processor.PageProcessor;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    public static void main(String[] args) {

        Spider spider = Spider.create(new ZgkejizxDualDbCrawler())
                .addUrl("http://www.zgkejizx.com/").thread(3);

        ZgkejizxDualDbCrawler.DualDbPipeline pipeline = new ZgkejizxDualDbCrawler.DualDbPipeline();
        pipeline.setSpider(spider);
        spider.addPipeline(pipeline).run();

    }
}