FROM eclipse-temurin:21-jre-jammy

ARG FINERACT_VERSION=1.15.0
ARG FINERACT_SHA512=0DB5983C228ED6C96DC2E9D8918F570172758ACCEB898525D39C44684E9125215D59C1B64F8C85BA18141EA3D9C0C4955F3A670681464784F2AF06D34B74CE1C

# Docker Hub không có image cho commit release 1.15.0. Fixture tự đóng gói
# binary chính thức của Apache và xác minh SHA-512 trước khi giải nén.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl netcat-openbsd \
    && rm -rf /var/lib/apt/lists/* \
    && curl --fail --location --retry 3 \
       "https://archive.apache.org/dist/fineract/${FINERACT_VERSION}/apache-fineract-bin-${FINERACT_VERSION}.tar.gz" \
       --output /tmp/fineract.tar.gz \
    && echo "${FINERACT_SHA512}  /tmp/fineract.tar.gz" | sha512sum --check --strict \
    && mkdir --parents /opt/fineract \
    && tar --extract --gzip --file /tmp/fineract.tar.gz --directory /opt/fineract --strip-components=1 \
    && rm /tmp/fineract.tar.gz

WORKDIR /opt/fineract

EXPOSE 8443

ENTRYPOINT ["java", "-Dloader.path=libs/", "-jar", "fineract-provider-1.15.0.jar"]
