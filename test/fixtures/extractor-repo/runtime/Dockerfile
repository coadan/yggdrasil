FROM eclipse-temurin:21-jdk AS deps
WORKDIR /workspace
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies

FROM deps AS build
COPY src ./src
RUN ./gradlew installDist

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
ENV PANEL_ENV=production PANEL_DEBUG=false
COPY --from=build /workspace/build/install/panels /app
EXPOSE 8080
CMD ["/app/bin/panels"]
