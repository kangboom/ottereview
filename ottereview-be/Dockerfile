FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY build/libs/*.jar app.jar
# The key is kept outside the packaged classpath and loaded from the container filesystem.
COPY src/main/resources/kb-github-app-private-key.der /app/config/github-app-private-key.der
ENTRYPOINT ["java", "-jar", "app.jar"]
