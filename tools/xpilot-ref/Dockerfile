# syntax=docker/dockerfile:1
#
# Reference build of the ORIGINAL C XPilot 4.5.5 (server + X11 client).
#
# The XPilot source is NOT part of this directory (it is GPL-2.0-or-later
# and is not vendored).  It is supplied as the named build context "xpsrc":
#
#   docker build --build-context xpsrc=/path/to/xpilot-4.5.5 -t xpilot-ref:4.5.5 .
#
# build.sh does this for you (XPILOT_SRC env var selects the source tree).

ARG DEBIAN_TAG=bookworm-slim

############################ builder ############################
FROM debian:${DEBIAN_TAG} AS builder
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      build-essential xutils-dev libx11-dev libxext-dev \
 && rm -rf /var/lib/apt/lists/*

COPY --from=xpsrc . /build/xpilot-4.5.5
COPY patches/ /build/patches/
WORKDIR /build/xpilot-4.5.5

# Upstream Imake build, PREFIX=/usr/local (Local.config default), so the
# server's compiled-in LIBDIR is /usr/local/lib/xpilot/.
RUN set -e; \
    for p in /build/patches/*.patch; do echo "applying $p"; patch -p1 < "$p"; done; \
    xmkmf -a > /build/xmkmf.log 2>&1; \
    make > /build/make.log 2>&1 || { tail -60 /build/make.log; exit 1; }; \
    make install > /build/install.log 2>&1 || { tail -60 /build/install.log; exit 1; }; \
    ls -l /usr/local/bin/xpilot /usr/local/bin/xpilots; \
    ls /usr/local/lib/xpilot/maps | head -50

############################ runtime ############################
FROM debian:${DEBIAN_TAG}
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libx11-6 libxext6 \
      xvfb x11-apps x11-utils xdotool imagemagick \
      xfonts-base xfonts-75dpi xfonts-100dpi fonts-dejavu-core fontconfig \
      procps coreutils \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /usr/local/bin/ /usr/local/bin/
COPY --from=builder /usr/local/lib/xpilot/ /usr/local/lib/xpilot/
COPY --from=builder /build/make.log /usr/local/share/xpilot-ref/make.log
COPY container/ /usr/local/share/xpilot-ref/
RUN chmod +x /usr/local/share/xpilot-ref/*.sh \
 && useradd -m -s /bin/bash pilot
USER pilot
WORKDIR /home/pilot
ENV XPILOT_LIB=/usr/local/lib/xpilot
