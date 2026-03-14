FROM eclipse-temurin:21-jre-alpine

ARG DATOMIC_VERSION=1.0.7556

RUN apk add --no-cache bash curl unzip gettext

RUN curl -fsSL "https://datomic-pro-downloads.s3.amazonaws.com/${DATOMIC_VERSION}/datomic-pro-${DATOMIC_VERSION}.zip" \
      -o /opt/datomic-pro.zip \
    && unzip /opt/datomic-pro.zip -d /opt/ \
    && mv /opt/datomic-pro-${DATOMIC_VERSION} /opt/datomic \
    && rm /opt/datomic-pro.zip

COPY docker/datomic/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 4334 4335

ENTRYPOINT ["/entrypoint.sh"]
