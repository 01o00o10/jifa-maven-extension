FROM node:18
RUN apt-get update \
    && apt-get install openjdk-17-jdk -y \
    && apt-get clean
WORKDIR /workspace/
COPY . /workspace/
ARG MAVEN_ARGS="clean package -DskipTests"
RUN --mount=type=cache,target=/root/.m2 mvn -B $MAVEN_ARGS
RUN mkdir -p server/target/dependency && (cd server/target/dependency; jar -xf ../jifa.jar)
