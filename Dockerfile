FROM eclipse-temurin:21-jre

WORKDIR /app

# 컨테이너 헬스체크(/actuator/health/liveness)용 curl. temurin JRE 에 기본 미포함이라 설치한다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ENV SPRING_PROFILES_ACTIVE=prod
ENV PORT=8080

COPY build/libs/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
