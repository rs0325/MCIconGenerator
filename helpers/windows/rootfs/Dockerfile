FROM ubuntu:noble@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852 AS runtime
ARG SOURCE_DATE_EPOCH=1793664000
ADD --checksum=sha256:641de77d8f142cfd62a1a6f964ba67b20754d3337c480efb529d086075a06c9a https://snapshot.ubuntu.com/ubuntu/20261103T000000Z/pool/main/c/ca-certificates/ca-certificates_20240203_all.deb /tmp/ca.deb
COPY helpers/windows/rootfs/packages.lock /tmp/packages.lock
RUN dpkg-deb -x /tmp/ca.deb /tmp/bootstrap-ca \
    && cat /tmp/bootstrap-ca/usr/share/ca-certificates/mozilla/*.crt > /tmp/bootstrap.crt \
    && sed -i -E 's#http://(archive.ubuntu.com/ubuntu|security.ubuntu.com/ubuntu)/?#https://snapshot.ubuntu.com/ubuntu/20261103T000000Z/#g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::https::CaInfo=/tmp/bootstrap.crt update --error-on=any \
    && apt-cache show ca-certificates=20240203 | grep -Fx 'SHA256: 641de77d8f142cfd62a1a6f964ba67b20754d3337c480efb529d086075a06c9a' \
    && PYTHONHASHSEED=0 DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::https::CaInfo=/tmp/bootstrap.crt install -y --no-install-recommends $(cat /tmp/packages.lock) \
    && rm -rf /var/lib/apt/lists/* /tmp/ca.deb /tmp/bootstrap-ca /tmp/bootstrap.crt /tmp/packages.lock \
        /var/log/apt/* /var/log/dpkg.log /var/log/alternatives.log /var/cache/ldconfig/aux-cache

FROM runtime AS probe
COPY helpers/windows/rootfs/probe-packages.lock /tmp/probe-packages.lock
RUN apt-get update --error-on=any \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $(cat /tmp/probe-packages.lock)
COPY helpers/macos/src/main/native/cpu-probe.S /tmp/cpu-probe.S
RUN as --64 -o /tmp/cpu-probe.o /tmp/cpu-probe.S \
    && ld -m elf_x86_64 --build-id=none -o /tmp/cpu-probe /tmp/cpu-probe.o

FROM runtime
COPY --from=probe --chmod=0755 /tmp/cpu-probe /usr/lib/mcicon/cpu-probe
COPY --chmod=0644 helpers/windows/src/main/guest/guestbridge.py /usr/lib/mcicon/guestbridge.py
COPY --chmod=0644 LICENSE /usr/share/doc/mcicon/copyright
