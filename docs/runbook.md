# 从零启动项目

## 前置条件

- Java 21
- Maven 3.9+
- Docker（用于 MySQL、Redis、Qdrant）
- 或本地安装 MySQL、Redis

## 启动步骤

### 1. 启动 MySQL

```bash
# Docker 方式
docker run -d --name mysql \
    -e MYSQL_ROOT_PASSWORD=root123 \
    -e MYSQL_USER=ai_dev \
    -e MYSQL_PASSWORD=your_password \
    -p 3306:3306 \
    mysql:8.0
```

### 2. 启动 Redis

```bash
# Docker 方式
docker run -d --name redis -p 6379:6379 redis:7
```

### 3. 启动 Qdrant

```bash
bash scripts/start_qdrant.sh
```

### 4. 初始化数据库

```bash
source .env
bash scripts/init_db.sh
```

### 5. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填入 DB_PASSWORD
source .env
```

### 6. 启动 Spring Boot

```bash
mvn spring-boot:run
```

或者临时指定密码：

```bash
DB_PASSWORD=your_password mvn spring-boot:run
```

### 7. 打开 Swagger

```
http://localhost:8080/swagger-ui.html
```

### 8. 执行 RAG 流程测试

```bash
source scripts/test_rag_flow.sh
```

## 验证

访问 `http://localhost:8080/swagger-ui.html`，应看到以下接口分组：

- 文档管理接口
- 文档解析接口
- 文档向量化接口
- 语义检索接口
- RAG 问答接口