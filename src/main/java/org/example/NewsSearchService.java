package org.example;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

@Service
public class NewsSearchService {

    // 索引文件存放路径（放到 /opt 下，保证持久化）
    private static final String INDEX_PATH = "/opt/news_lucene_index";
    private Analyzer analyzer = new SmartChineseAnalyzer();
    private Directory directory;

    @PostConstruct
    public void init() throws IOException {
        // 初始化索引目录
        this.directory = FSDirectory.open(Paths.get(INDEX_PATH));
    }

    /**
     * 核心查询逻辑
     */
    public Map<String, Object> searchNews(
            String keyword, String topic, String source,
            String startTime, String endTime,
            int pageNum, int pageSize
    ) {
        Map<String, Object> result = new HashMap<>();
        List<NewsDoc> pageList = new ArrayList<>();
        int actualPageNum = Math.max(1, pageNum);

        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            BooleanQuery.Builder mainQuery = new BooleanQuery.Builder();

            // 1. 🌟 关键词模糊查询：引入“权重分配”机制！
            if (keyword != null && !keyword.isBlank()) {
                // 定义字段权重：标题的权重是 5.0，正文是 1.0
                Map<String, Float> boosts = new HashMap<>();
                boosts.put("title", 5.0f);
                boosts.put("content", 1.0f);

                MultiFieldQueryParser parser = new MultiFieldQueryParser(new String[]{"title", "content"}, analyzer, boosts);
                mainQuery.add(parser.parse(keyword), BooleanClause.Occur.MUST);
            } else {
                // 如果没有输入关键词，就默认匹配全库文章（当做首页信息流）
                mainQuery.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
            }

            // 2. 🌟 升级版精确过滤：支持多选 (Topic / Source)
            if (topic != null && !topic.isBlank()) {
                String[] topics = topic.split(",");
                BooleanQuery.Builder topicQuery = new BooleanQuery.Builder();
                for (String t : topics) {
                    // SHOULD 相当于 SQL 里的 OR (只要匹配其中一个分类即可)
                    topicQuery.add(new TermQuery(new Term("topic", t.trim())), BooleanClause.Occur.SHOULD);
                }
                // MUST 意味着“必须满足上面那个 OR 条件组”
                mainQuery.add(topicQuery.build(), BooleanClause.Occur.MUST);
            }

            if (source != null && !source.isBlank()) {
                String[] sources = source.split(",");
                BooleanQuery.Builder sourceQuery = new BooleanQuery.Builder();
                for (String s : sources) {
                    sourceQuery.add(new TermQuery(new Term("source", s.trim())), BooleanClause.Occur.SHOULD);
                }
                mainQuery.add(sourceQuery.build(), BooleanClause.Occur.MUST);
            }

            // 3. 时间范围过滤 (Lucene 的 TermRangeQuery)
            if (startTime != null && startTime.length() >= 10 && endTime != null && endTime.length() >= 10) {
                mainQuery.add(TermRangeQuery.newStringRange("publish_time",
                                startTime.substring(0, 10), endTime.substring(0, 10), true, true),
                        BooleanClause.Occur.MUST);
            }

            // 4. 🌟 执行搜索 & 排序 (按场景动态排序)
            Sort sort;
            if (keyword != null && !keyword.isBlank()) {
                // 有关键词：优先按 Lucene 计算的相关性得分(FIELD_SCORE)降序排列，得分相同时再按时间倒序！
                sort = new Sort(SortField.FIELD_SCORE, new SortField("publish_time", SortField.Type.STRING, true));
            } else {
                // 没关键词(看首页)：单纯按发布时间倒序排列！
                sort = new Sort(new SortField("publish_time", SortField.Type.STRING, true));
            }

            // 为了分页，我们先拿到 TopDocs
            TopFieldDocs topDocs = searcher.search(mainQuery.build(), actualPageNum * pageSize, sort);
            ScoreDoc[] hits = topDocs.scoreDocs;

            // 5. 分页逻辑处理
            int start = (actualPageNum - 1) * pageSize;
            int end = Math.min(start + pageSize, hits.length);

            for (int i = start; i < end; i++) {
                Document doc = searcher.doc(hits[i].doc);
                pageList.add(documentToNewsDoc(doc));
            }

            result.put("total", topDocs.totalHits.value);
            result.put("pageNum", actualPageNum);
            result.put("pageSize", pageSize);
            result.put("list", pageList);
            result.put("content", pageList);

        } catch (Exception e) {
            e.printStackTrace();
            result.put("total", 0);
            result.put("list", new ArrayList<>());
        }
        return result;
    }

    /**
     * 保存/更新单条新闻
     */
    public void saveNews(NewsDoc newsDoc) {
        if (newsDoc.getId() == null) newsDoc.setId(UUID.randomUUID().toString());

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            Document doc = newsDocToDocument(newsDoc);
            writer.updateDocument(new Term("id", newsDoc.getId()), doc);
            writer.commit();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Lucene 版：根据 ID 查找单条新闻
     */
    public NewsDoc findById(String id) {
        if (id == null || id.isBlank()) return null;

        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query query = new TermQuery(new Term("id", id));
            TopDocs topDocs = searcher.search(query, 1);

            if (topDocs.totalHits.value > 0) {
                Document doc = searcher.doc(topDocs.scoreDocs[0].doc);
                return documentToNewsDoc(doc);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 批量保存
     */
    public void batchSaveNews(List<NewsDoc> list) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (NewsDoc newsDoc : list) {
                if (newsDoc.getId() == null) newsDoc.setId(UUID.randomUUID().toString());
                writer.updateDocument(new Term("id", newsDoc.getId()), newsDocToDocument(newsDoc));
            }
            writer.commit();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 清空索引
     */
    public void clearAll() {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            writer.deleteAll();
            writer.commit();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ====================== 转换辅助方法 ======================

    private Document newsDocToDocument(NewsDoc newsDoc) {
        Document doc = new Document();
        doc.add(new StringField("id", newsDoc.getId(), Field.Store.YES));
        doc.add(new TextField("title", newsDoc.getTitle(), Field.Store.YES));
        doc.add(new TextField("content", newsDoc.getContent(), Field.Store.YES));
        doc.add(new StringField("publish_time", newsDoc.getPublishTime(), Field.Store.YES));
        doc.add(new SortedDocValuesField("publish_time", new org.apache.lucene.util.BytesRef(newsDoc.getPublishTime())));
        doc.add(new StringField("topic", newsDoc.getTopic() == null ? "" : newsDoc.getTopic(), Field.Store.YES));
        doc.add(new StringField("source", newsDoc.getSource() == null ? "" : newsDoc.getSource(), Field.Store.YES));
        doc.add(new StringField("url", newsDoc.getUrl() == null ? "" : newsDoc.getUrl(), Field.Store.YES));
        return doc;
    }

    private NewsDoc documentToNewsDoc(Document doc) {
        NewsDoc news = new NewsDoc();
        news.setId(doc.get("id"));
        news.setTitle(doc.get("title"));
        news.setContent(doc.get("content"));
        news.setPublishTime(doc.get("publish_time"));
        news.setTopic(doc.get("topic"));
        news.setSource(doc.get("source"));
        news.setUrl(doc.get("url"));
        return news;
    }
    /**
     * 获取 Lucene 索引中某一个字段下所有可能的词（Term）并去重返回。
     * 可以用来获取所有的新闻来源（source）或者所有的分类（topic）
     */
    public List<String> getTerms(String field) {
        Set<String> ts = new HashSet<>();
        try (IndexReader reader = DirectoryReader.open(directory)) {
            List<LeafReaderContext> contexts = reader.leaves();
            BytesRef ref;
            for (LeafReaderContext context : contexts) {
                LeafReader leafReader = context.reader();
                Terms terms = leafReader.terms(field);
                if (terms == null) continue;

                TermsEnum termsEnum = terms.iterator();
                while ((ref = termsEnum.next()) != null) {
                    ts.add(ref.utf8ToString());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("🔍 共在字段[" + field + "]中找到[" + ts.size() + "]个关键词: " + ts);
        return new ArrayList<>(ts);
    }
}