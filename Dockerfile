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

# Stała nazwa jara przed rozpakowaniem. Nazwa z targetu niesie wersję
# (file_translator-0.0.1-SNAPSHOT.jar), a ta trafia potem do ENTRYPOINT - przy
# pierwszym podbiciu wersji obraz przestałby startować.
RUN cp target/*.jar application.jar

# Rozpakowanie fat-jara na warstwy. Zależności zmieniają się rzadko, a kod aplikacji
# przy każdym commicie - rozdzielenie ich na osobne warstwy sprawia, że wdrożenie
# przesyła kilkaset kilobajtów zamiast kilkudziesięciu megabajtów.
# Uwaga: w Spring Boot 3.3+ tryb "layertools" zastąpiono trybem "tools".
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

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

# Katalog na logi i właściciel samego /app.
#
# WORKDIR wyżej tworzy /app jako ROOT, bo USER spring stoi dopiero pod spodem,
# a COPY --chown zmienia właściciela KOPIOWANYCH wpisów, nie katalogu, do którego
# trafiają. Profil prod pisze do logs/app.log, czyli ścieżki WZGLĘDNEJ - rozwiązuje
# się względem /app, a użytkownik spring nie miał tam prawa zapisu.
#
# Objaw nie był łagodny: logback zgłasza wtedy "Failed to create parent directories",
# a Spring Boot 4 podnosi błędy konfiguracji logowania do wyjątku ("Logback
# configuration error detected"), więc kontener na profilu prod NIE WSTAWAŁ W OGÓLE.
# Na dev problem nie występuje i dlatego był niewidoczny: żaden profil poza prod nie
# ustawia logging.file.name, więc logback spada na ${java.io.tmpdir}/spring.log.
RUN mkdir -p /app/logs && chown -R spring:spring /app

USER spring
EXPOSE 8080

# MaxRAMPercentage zamiast sztywnego -Xmx: JVM sam wylicza stertę z limitu pamięci
# kontenera, więc ten sam obraz działa poprawnie przy różnych limitach.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Uruchamiamy cienki jar z warstwy "application", NIE przez JarLauncher.
#
# Tryb "tools" w Spring Boot 4 rozkłada archiwum inaczej niż dawny "layertools":
# w application/ leży zwykły jar z Main-Class i z Class-Path wskazującym na lib/,
# zależności lądują w dependencies/lib/, a katalog spring-boot-loader/ jest PUSTY -
# klasa org.springframework.boot.loader.launch.JarLauncher w tym układzie nie
# istnieje. Poprzedni ENTRYPOINT wywoływał ją wprost, więc obraz budował się
# poprawnie i wywalał się dopiero przy starcie kontenera na ClassNotFoundException.
# Warstwy kopiowane są płasko do /app, dlatego względne lib/... z manifestu trafia
# dokładnie tam, gdzie leżą zależności.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
