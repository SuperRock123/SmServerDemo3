FROM maven:3.8.6-eclipse-temurin-17-alpine AS builder

WORKDIR /app
COPY . .
RUN mkdir -p /root/.m2/repository/com/example/local-lib/1.2/
RUN cp lib/smserver-heigh-1.2.jar /root/.m2/repository/com/example/local-lib/1.2/local-lib-1.2.jar
RUN mvn clean package -DskipTests -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /app/target/ToeHold-1.0.1.jar app.jar

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