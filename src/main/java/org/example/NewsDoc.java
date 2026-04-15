package org.example;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NewsDoc {
    private String id;
    private String title;
    private String summary;
    private String content;
    private String source;
    private String topic;
    private LocalDateTime publishTime;
    private String coverImageUrl;
    private String originalUrl;
    private Long viewCount;
    private LocalDateTime crawlTime;
}