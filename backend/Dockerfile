# JobRadar 后端 Dockerfile（多阶段构建：编译 → 运行）
# 用法：docker build -f backend/Dockerfile -t jobradar-app backend/
#
# 设计要点：
# - 阶段1用 Maven 缓存依赖层：pom.xml 不变时复用 ~/.m2，加速重建
# - 阶段2用 JRE 而非 JDK（-slim 更小），仅含运行时
# - 启动参数留出口：JAVA_OPTS / SPRING_OPTS 可在 docker run -e 注入

# ---------- 阶段1：编译 ----------
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# 先拷贝 pom 利用 Docker 缓存层：依赖不变时不重新 download
COPY pom.xml .
COPY jobradar-core/pom.xml jobradar-core/
COPY jobradar-app/pom.xml jobradar-app/
COPY jobradar-mcp-server/pom.xml jobradar-mcp-server/
RUN mvn dependency:go-offline -pl jobradar-app -am -q

# 再拷贝源码并打包（-DskipTests 加速，测试在 CI 跑）
COPY jobradar-core/src jobradar-core/src
COPY jobradar-app/src jobradar-app/src
COPY jobradar-mcp-server/src jobradar-mcp-server/src
RUN mvn package -pl jobradar-app -am -DskipTests -q

# ---------- 阶段2：运行 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 飞书/中文简历解析需要时区与字体支持（Alpine 默认无亚洲字体，PDFBox 可能乱码）
RUN apk add --no-cache tzdata font-noto-cjk
ENV TZ=Asia/Shanghai

# 简历存储目录（容器内路径；生产应挂 PVC/Volume）
RUN mkdir -p /data/resumes
ENV JOBRADAR_RESUME_DIR=/data/resumes

# 端口：8080 是默认，可通过环境变量覆盖
EXPOSE 8080

# 健康检查（Spring Boot Actuator 未引入，用根路径 404 也可接受；
# 若后续加 actuator，改为 /actuator/health）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/dashboard/summary || exit 1

COPY --from=builder /build/jobradar-app/target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar ${SPRING_OPTS}"]
