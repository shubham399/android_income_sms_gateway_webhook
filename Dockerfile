# Android build environment for the Webhook Bridge APK.
#
# A self-contained builder so the project can be compiled on any machine
# with Docker (no local JDK / Android SDK required). Usage:
#
#     docker build -t ws-android-builder .
#     docker run --rm \
#       -v $(pwd):/src \
#       -v ws-gradle-cache:/root/.gradle \
#       ws-android-builder \
#       ./gradlew --no-daemon assembleDebug
#
# The APK lands in app/build/outputs/apk/debug/ on the host (the project
# directory is bind-mounted). See the Makefile for one-command wrappers.

FROM ubuntu:24.04

ENV ANDROID_HOME=/opt/android-sdk
ENV DEBIAN_FRONTEND=noninteractive
# services.gradle.org / Maven can be flaky over IPv6; force the JVM to IPv4.
ENV GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"

# JDK 17 is the minimum for AGP 9.x.
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Android SDK command-line tools (only tooling, no CLI bundled UI).
RUN mkdir -p "$ANDROID_HOME/cmdline-tools/latest" \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline.zip \
    && unzip -q /tmp/cmdline.zip -d /tmp/cmdline-extract \
    && mv /tmp/cmdline-extract/cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/" \
    && rmdir /tmp/cmdline-extract/cmdline-tools \
    && rm -rf /tmp/cmdline.zip /tmp/cmdline-extract

# Accept licenses and pre-install what the build needs. compileSdk lives in
# app/build.gradle -- bump the "platforms;android-X" line to match it.
RUN yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null \
    && "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0"

WORKDIR /src