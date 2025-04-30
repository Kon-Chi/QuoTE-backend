FROM sbtscala/scala-sbt:graalvm-ce-22.3.3-b1-java17_1.10.11_3.6.4 AS sbt
WORKDIR /build
COPY project /build/project
COPY build.sbt /build/
COPY src /build/src
EXPOSE 8080
RUN sbt compile
CMD sbt run
