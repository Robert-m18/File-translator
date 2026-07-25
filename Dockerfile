# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Etap 1: budowanie
# Kompilacja dzieje się w kontenerze, więc obraz powstaje identycznie na moim
# laptopie i na CI - bez zależności od tego, jakie JDK ma zainstalowany host.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Najpierw same deskryptory zależności. Docker cache'uje warstwy: dopóki pom.xml
# się nie zmieni, warstwa z pobranymi zależnościami jest odtwarzana z cache'u,
# zamiast ciągnąć pół internetu przy każdej zmianie w kodzie.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# Testy uruchamia CI (i to na prawdziwej bazie) - powtarzanie ich tutaj tylko
# wydłużałoby budowanie obrazu.
RUN ./mvnw -B -ntp clean package -DskipTests

# Rozpakowanie fat-jara na warstwy. Zależności zmieniają się rzadko, a kod aplikacji
# przy każdym commicie - rozdzielenie ich na osobne warstwy sprawia, że wdrożenie
# przesyła kilkaset kilobajtów zamiast kilkudziesięciu megabajtów.
# Uwaga: w Spring Boot 3.3+ tryb "layertools" zastąpiono trybem "tools".
RUN java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted

# ---------------------------------------------------------------------------
# Etap 2: obraz uruchomieniowy
# Zawiera samo JRE i aplikację - bez JDK, Mavena, źródeł i cache'u zależności.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Proces nie działa jako root: przejęcie aplikacji nie daje wtedy od razu
# pełnej kontroli nad kontenerem.
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app

COPY --from=build --chown=spring:spring /build/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /build/extracted/application/ ./

USER spring
EXPOSE 8080

# MaxRAMPercentage zamiast sztywnego -Xmx: JVM sam wylicza stertę z limitu pamięci
# kontenera, więc ten sam obraz działa poprawnie przy różnych limitach.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
