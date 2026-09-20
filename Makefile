# Build the Webhook Bridge APK inside a Docker container, so no local
# JDK / Android SDK is needed. Everything the build requires is baked into
# the image defined by ./Dockerfile; the project directory is bind-mounted
# read-write so Gradle outputs (build/, and the APK) appear on the host.
#
# Targets:
#   make image   build (or refresh) the builder Docker image
#   make build   assemble the debug APK (image is built first)
#   make apk     build and copy the APK to dist/webhook-bridge-debug.apk
#   make release assemble the release APK and copy it to
#                dist/webhook-bridge-release.apk
#   make test    run the JVM unit tests (testDebugUnitTest)
#   make clean   delete local build/ output (does NOT touch the Docker image)

IMAGE              := ws-android-builder
DOCKERFILE         := Dockerfile
GRADLE_CACHE_VOLUME := ws-gradle-cache

DOCKER_RUN := docker run --rm \
	-v $(CURDIR):/src \
	-v $(GRADLE_CACHE_VOLUME):/root/.gradle \
	$(IMAGE)

.PHONY: image build apk release test clean

image:
	docker build -t $(IMAGE) -f $(DOCKERFILE) .

build: image
	$(DOCKER_RUN) ./gradlew --no-daemon assembleDebug

apk: build
	mkdir -p dist
	cp $$(ls -t app/build/outputs/apk/debug/*.apk | head -1) dist/webhook-bridge-debug.apk
	@echo "APK copied to dist/webhook-bridge-debug.apk"

release: image
	$(DOCKER_RUN) ./gradlew --no-daemon assembleRelease
	mkdir -p dist
	cp $$(ls -t app/build/outputs/apk/release/*.apk | head -1) dist/webhook-bridge-release.apk
	@echo "APK copied to dist/webhook-bridge-release.apk"

test: image
	$(DOCKER_RUN) ./gradlew --no-daemon testDebugUnitTest

clean:
	rm -rf build app/build