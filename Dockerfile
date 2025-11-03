FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /app/target/ToeHold-1.0.1-shaded.jar app.jar

# 创建配置目录
RUN mkdir -p /app/config

# 设置卷挂载点，支持外部配置文件映射
VOLUME ["/app/config"]

# 设置默认的配置文件路径环境变量
ENV CONFIG_PATH="/app/config/app.json"

# 暴露TCP端口
EXPOSE 9911

# 启动命令
CMD ["java", "-jar", "app.jar"]