# Production image: the Spring Boot jar plus the headless Bambu Studio CLI on Ubuntu 24.04.
# The development stack mounts the source into a Maven image instead (pod-frontend repository,
# docker/backend-slicer/Dockerfile); this file is what runs on the server.

# ---- build the jar -------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B --no-transfer-progress -DskipTests package \
    && cp target/cad-slicer-engine-*.jar /app.jar

# ---- runtime -------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

ARG DEBIAN_FRONTEND=noninteractive
ARG BAMBU_TAG=v02.08.02.61
ARG BAMBU_APPIMAGE=BambuStudio_ubuntu24.04-v02.08.02.61-20260820225108.AppImage
# Pinned from the release downloaded on 2026-10-18; a changed file fails the build.
ARG BAMBU_SHA256=d501b103fac5424513ec0e8d6bc145fb30719de2c7d94d7320d723740c81a7fd

# Shared libraries the AppImage links against (it is a GTK desktop build run headless).
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl file locales mesa-utils \
    libasound2t64 libatk-bridge2.0-0t64 libatk1.0-0t64 libcairo2 libcurl4 libdbus-1-3 libdrm2 \
    libegl1 libfontconfig1 libfreetype6 libgbm1 libgdk-pixbuf-2.0-0 libgl1 libgles2 libglu1-mesa \
    libglx-mesa0 libgstreamer-gl1.0-0 libgstreamer-plugins-base1.0-0 libgstreamer1.0-0 \
    libgtk-3-0t64 libharfbuzz0b libice6 libnotify4 libnss3 libosmesa6 libpango-1.0-0 \
    libpangocairo-1.0-0 libsecret-1-0 libsoup-3.0-0 libsm6 libwebkit2gtk-4.1-0 libx11-6 \
    libxcursor1 libxdamage1 libxi6 libxinerama1 libxkbcommon0 libxrandr2 libxtst6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/bambu
RUN curl -fsSL -o BambuStudio.AppImage \
    "https://github.com/bambulab/BambuStudio/releases/download/${BAMBU_TAG}/${BAMBU_APPIMAGE}" \
    && echo "${BAMBU_SHA256}  BambuStudio.AppImage" | sha256sum -c - \
    && chmod +x BambuStudio.AppImage \
    && ./BambuStudio.AppImage --appimage-extract >/dev/null \
    && rm BambuStudio.AppImage \
    && ln -sf /opt/bambu/squashfs-root/AppRun /usr/local/bin/bambu-studio-cli

# Unprivileged runtime user; the order files live on a volume mounted here.
RUN useradd --system --create-home --uid 10001 app \
    && mkdir -p /data/order-files \
    && chown -R app:app /data

WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
ENV SLICER_BINARY_PATH=bambu-studio-cli \
    ORDER_FILES_DIR=/data/order-files \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/orders/settings >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
