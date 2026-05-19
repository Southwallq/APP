package org.example;

import java.util.List;

/**
 * NewsDoc - 纯净版 POJO
 * 不再依赖任何 ES 注解，只负责存储数据字段
 */
public class NewsDoc {

    // 核心字段
    private String id;
    private String title;
    private String content;
    private String url;
    private String publishTime; // 对应之前的 publish_time
    private List<String> images;
    private String source;
    private String topic;

    // ======= 无参构造函数 (Jackson 反序列化时需要) =======
    public NewsDoc() {}

    // ======= 全参构造函数 (方便代码里快速创建对象) =======
    public NewsDoc(String id, String title, String content, String url, String publishTime, List<String> images, String source, String topic) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.url = url;
        this.publishTime = publishTime;
        this.images = images;
        this.source = source;
        this.topic = topic;
    }

    // ======= 标准 Getter 和 Setter =======
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getPublishTime() { return publishTime; }
    public void setPublishTime(String publishTime) { this.publishTime = publishTime; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    // 为了调试方便，建议生成一个 toString 方法
    @Override
    public String toString() {
        return "NewsDoc{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", source='" + source + '\'' +
                '}';
    }
}