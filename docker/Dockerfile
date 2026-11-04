FROM eclipse-temurin:8-jdk-noble@sha256:55cdca1740b7bcfc1dc207744bd280e1ff2b7829475185745e1b5d19c3e42f97 AS java8
FROM eclipse-temurin:17-jdk-noble@sha256:fb79620c3f6ee8f1c7530a8dc4fd49e3b410bcef037694ad4858a1553bac4170 AS java17
FROM eclipse-temurin:21-jdk-noble@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87 AS java21
FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4
COPY --from=java8 /opt/java/openjdk /opt/java/java8
COPY --from=java17 /opt/java/openjdk /opt/java/java17
COPY --from=java21 /opt/java/openjdk /opt/java/java21
ARG SOURCE_DATE_EPOCH=1793664000
COPY docker/packages.lock /tmp/renderer-packages.lock
RUN sed -i -E 's#http://(archive.ubuntu.com/ubuntu|security.ubuntu.com/ubuntu)/?#https://snapshot.ubuntu.com/ubuntu/20261103T000000Z/#g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update --error-on=any \
    && PYTHONHASHSEED=0 apt-get install -y --no-install-recommends $(cat /tmp/renderer-packages.lock) \
    && rm -rf /var/lib/apt/lists/* /tmp/renderer-packages.lock \
        /var/log/apt/* /var/log/dpkg.log /var/log/alternatives.log \
        /var/cache/ldconfig/aux-cache
ENV LIBGL_ALWAYS_SOFTWARE=1
ENV LP_NATIVE_VECTOR_WIDTH=128
ENV GALLIUM_OVERRIDE_CPU_CAPS=sse4.1
ENV ALSOFT_DRIVERS=null
WORKDIR /workspace
COPY docker/entrypoint.sh /usr/local/bin/mcicon-display
RUN chmod +x /usr/local/bin/mcicon-display
ENTRYPOINT ["/usr/local/bin/mcicon-display"]
