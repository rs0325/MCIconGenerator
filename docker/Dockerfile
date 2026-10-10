FROM eclipse-temurin:8-jdk-noble@sha256:55cdca1740b7bcfc1dc207744bd280e1ff2b7829475185745e1b5d19c3e42f97 AS java8
FROM eclipse-temurin:17-jdk-noble@sha256:fb79620c3f6ee8f1c7530a8dc4fd49e3b410bcef037694ad4858a1553bac4170 AS java17
FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87 AS java21
FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4
COPY --from=java8 /opt/java/openjdk /opt/java/java8
COPY --from=java17 /opt/java/openjdk /opt/java/java17
COPY --from=java21 /opt/java/openjdk /opt/java/java21
RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb xauth libgl1 libgl1-mesa-dri mesa-utils libxrandr2 libxcursor1 \
    libxi6 libxrender1 libxtst6 libxxf86vm1 libopenal1 python3 curl \
    && rm -rf /var/lib/apt/lists/*
ENV LIBGL_ALWAYS_SOFTWARE=1
ENV ALSOFT_DRIVERS=null
WORKDIR /workspace
COPY docker/entrypoint.sh /usr/local/bin/mcicon-display
RUN chmod +x /usr/local/bin/mcicon-display
ENTRYPOINT ["/usr/local/bin/mcicon-display"]
