# Getting Started

## Docker

```shell
$ docker run -p 8102:8102 eclipsejifa/jifa
```

[Docker Image Link](https://hub.docker.com/r/eclipsejifa/jifa/tags)

## jifa.sh

```shell
# http://localhost:8102
$ curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash

# Change the port
$ curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- -p <port>

# Analyze local files
$ curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- <file1 path> <file2 path> ...

# Add JVM Options
$ curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- --jvm-options "<JVM options>"
```

::: tip Tip
[jifa.sh](https://github.com/eclipse/jifa/blob/main/jifa.sh) encapsulates `docker` command.
If you prefer to use the `docker` directly and also want to modify the server port or analyze local files,
please refer to it.
:::

## jifa-jpackage.{sh \| bat}

Use the `jpackage` command to package it into a binary installation package for easy local use (standalone mode).

## Releases

Platform support: linux/amd64, linux/arm64. For other platforms, please build from source code.

1. Download the latest release from [here](https://github.com/eclipse/jifa/releases).
2. `unzip jifa.zip`
3. `./jifa/bin/jifa`

## From Source

### Prerequisites

- JDK 17+
- Node.js 18+

### Run

```shell
# build once, then run as a standalone worker
$ mvn -B clean package
$ java -jar server/target/jifa.jar --jifa.role=standalone-worker --jifa.open-browser-when-ready=true

# run as master
$ java -jar server/target/jifa.jar --jifa.role=master

# run as static worker
$ java -jar server/target/jifa.jar --jifa.role=static-worker --jifa.port=9102
```

You can also run `Launcher` in the IDE.

For the introduction of roles, please refer to [deployment](./deployment.md).

### Frontend

Frontend code will be automatically bundled into the server, but it won't be automatically re-bundled after changes.
To facilitate the development and debugging of the frontend code, you can run a dev server with the following command:

```shell
$ cd frontend

$ npm ci

# http://localhost:8089
$ npm run dev
```

### Build

```shell
$ mvn -B clean install

# skip test
$ mvn -B clean package -DskipTests
```

The executable JAR and ZIP are in `./server/target`.

### Docker Image

Use [Dockerfile](https://github.com/eclipse/jifa/blob/main/Dockerfile) in the project root directory.
