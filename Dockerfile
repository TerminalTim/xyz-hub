FROM openjdk:8-alpine

MAINTAINER Benjamin Rögner "benjamin.roegner@here.com"
MAINTAINER Lucas Ceni "lucas.ceni@here.com"
MAINTAINER Dimitar Goshev "dimitar.goshev@here.com"

ENV LOG_CONFIG log4j2-console-plain.json
ENV HOST_NAME localhost
ENV FS_WEB_ROOT www

#Override the following environment variables to let the service connect to different host names
ENV STORAGE_DB_URL jdbc:postgresql://postgres/postgres
ENV PSQL_HOST postgres
ENV XYZ_HUB_REDIS_HOST redis

COPY xyzhub-service/target/xyzhub-service.jar .

EXPOSE 8080 8181

CMD java -jar xyzhub-service.jar

ADD Dockerfile-cicd /