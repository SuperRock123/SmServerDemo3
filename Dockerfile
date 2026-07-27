FROM maven:3.8.6-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# 使用国内 Maven 镜像，避免拉取 central 时 SSL 握手失败
COPY docker/maven-settings.xml /root/.m2/settings.xml

COPY . .
RUN mkdir -p /root/.m2/repository/com/example/local-lib/1.2/ \
 && cp lib/smserver-heigh-1.2.jar /root/.m2/repository/com/example/local-lib/1.2/local-lib-1.2.jar \
 && mvn -s /root/.m2/settings.xml clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /app/target/ToeHold-1.0.1.jar app.jar

RUN mkdir -p /app/config
VOLUME ["/app/config"]
ENV CONFIG_PATH="/app/config/app.json"

EXPOSE 9911
CMD ["java", "-jar", "app.jar"]
