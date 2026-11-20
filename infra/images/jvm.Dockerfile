ARG JAVA_IMAGE=eclipse-temurin:27-jre
FROM ${JAVA_IMAGE}
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/*-0.0.1-SNAPSHOT.jar /app/serviceops.jar
USER 65532
ENTRYPOINT ["java", "-jar", "/app/serviceops.jar"]
