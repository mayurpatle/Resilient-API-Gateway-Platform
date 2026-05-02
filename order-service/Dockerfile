# FROM eclipse-temurin:17-jdk-alpine AS build
# WORKDIR /workspace
# COPY . .
# RUN apk add --no-cache maven && mvn -q -DskipTests package
#
# FROM eclipse-temurin:17-jre-alpine
# WORKDIR /app
# COPY --from=build /workspace/target/*.jar app.jar
# EXPOSE 8082
# ENTRYPOINT ["java","-jar","/app/app.jar"]

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java","-jar","/app/app.jar"]