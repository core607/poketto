# syntax=docker/dockerfile:1

FROM docker.io/library/postgres:17.11-bookworm@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0 AS builder

ARG SCWS_COMMIT=a04ef5e655213eb6b07fb57761d0eac72c281ad4
ARG ZHPARSER_COMMIT=dd292fd591edbcb7ebee79d3c3cdc969e9cddced
ARG DEBIAN_FRONTEND=noninteractive

RUN set -eux; \
    apt-get update; \
    apt-get install --yes --no-install-recommends \
        autoconf \
        automake \
        binutils \
        build-essential \
        ca-certificates \
        git \
        libtool \
        pkg-config \
        postgresql-server-dev-${PG_MAJOR}; \
    rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    git init /tmp/scws; \
    git -C /tmp/scws remote add origin https://github.com/hightman/scws.git; \
    git -C /tmp/scws fetch --depth 1 origin "${SCWS_COMMIT}"; \
    git -C /tmp/scws checkout --detach FETCH_HEAD; \
    cd /tmp/scws; \
    touch README; \
    aclocal; \
    autoconf; \
    autoheader; \
    libtoolize; \
    automake --add-missing; \
    ./configure; \
    make --jobs="$(nproc)"; \
    make install

RUN set -eux; \
    git init /tmp/zhparser; \
    git -C /tmp/zhparser remote add origin https://github.com/amutu/zhparser.git; \
    git -C /tmp/zhparser fetch --depth 1 origin "${ZHPARSER_COMMIT}"; \
    git -C /tmp/zhparser checkout --detach FETCH_HEAD; \
    make --directory=/tmp/zhparser --jobs="$(nproc)"; \
    make --directory=/tmp/zhparser install

FROM docker.io/library/postgres:17.11-bookworm@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0

LABEL org.opencontainers.image.source="https://github.com/core607/poketto"
LABEL org.opencontainers.image.description="PostgreSQL 17 with zhparser for Poketto"

RUN localedef -i zh_CN -c -f UTF-8 -A /usr/share/locale/locale.alias zh_CN.UTF-8
ENV LANG=zh_CN.UTF-8

COPY --from=builder /usr/lib/postgresql/${PG_MAJOR}/lib/zhparser.so /usr/lib/postgresql/${PG_MAJOR}/lib/
COPY --from=builder /usr/local/lib/libscws.* /usr/local/lib/
COPY --from=builder /usr/share/postgresql/${PG_MAJOR}/extension/zhparser* /usr/share/postgresql/${PG_MAJOR}/extension/
COPY --from=builder /usr/lib/postgresql/${PG_MAJOR}/lib/bitcode/zhparser* /usr/lib/postgresql/${PG_MAJOR}/lib/bitcode/
COPY --from=builder /usr/share/postgresql/${PG_MAJOR}/tsearch_data/*.utf8.* /usr/share/postgresql/${PG_MAJOR}/tsearch_data/

RUN ldconfig
