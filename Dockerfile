FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew jar --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home keel \
    && mkdir -p /data \
    && chown keel:keel /data
USER keel
WORKDIR /data
COPY --from=build /src/build/libs/keel.jar /opt/keel/keel.jar
EXPOSE 6379
ENTRYPOINT ["java", "-jar", "/opt/keel/keel.jar"]
CMD ["--dir", "/data", "--port", "6379"]
