package org.example;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 数据库连接工具类 & 连通性测试
 */
public class JDBC {

    // --- MySQL 配置参数 ---
    private static final String MYSQL_URL = "jdbc:mysql://localhost:3306/spider_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    private static final String MYSQL_USER = "root";       // ⚠️ 确认你的用户名
    private static final String MYSQL_PASSWORD = "123456";   // ⚠️ 确认你的密码

    // --- MongoDB 配置参数 ---
    private static final String MONGO_URL = "mongodb://localhost:27017";
    private static final String MONGO_DB_NAME = "spider_db";

    /**
     * 获取 MySQL 连接对象
     */
    public static Connection getMysqlConnection() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        return DriverManager.getConnection(MYSQL_URL, MYSQL_USER, MYSQL_PASSWORD);
    }

    /**
     * 获取 MongoDB 集合对象
     */
    public static MongoCollection<Document> getMongoCollection(String collectionName) {
        MongoClient mongoClient = MongoClients.create(MONGO_URL);
        MongoDatabase database = mongoClient.getDatabase(MONGO_DB_NAME);
        return database.getCollection(collectionName);
    }

//    public static void main(String[] args) {
//        System.out.println("开始数据库连通性测试...");
//        System.out.println("------------------------------------------");
//
//        // 1. 测试 MySQL 连接
//        try (Connection conn = getMysqlConnection()) {
//            if (conn != null && !conn.isClosed()) {
//                System.out.println("✅ [MySQL] 连接成功！");
//
//                // 进一步测试：执行一个简单的查询语句
//                try (Statement stmt = conn.createStatement()) {
//                    ResultSet rs = stmt.executeQuery("SELECT VERSION()");
//                    if (rs.next()) {
//                        System.out.println("📊 [MySQL] 当前版本号: " + rs.getString(1));
//                    }
//                }
//            }
//        } catch (Exception e) {
//            System.err.println("❌ [MySQL] 连接失败！错误原因: " + e.getMessage());
//        }
//
//        System.out.println("------------------------------------------");
//
//        // 2. 测试 MongoDB 连接
//        try {
//            // 获取一个集合（如果不存在会自动创建，这也能测试连接）
//            MongoCollection<Document> collection = getMongoCollection("test_connection");
//
//            // 尝试统计集合里的文档数量（这是一个轻量级的真实请求）
//            long count = collection.countDocuments();
//
//            System.out.println("✅ [MongoDB] 连接成功！");
//            System.out.println("📊 [MongoDB] 当前测试集合文档数: " + count);
//
//        } catch (Exception e) {
//            System.err.println("❌ [MongoDB] 连接失败！错误原因: " + e.getMessage());
//        }
//
//    }
}
