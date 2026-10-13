#!/usr/bin/env bash
# Run litmus, the WebDAV test suite of the neon project, against the
# handler over a memory backend on Jetty.
#
#     dev/litmus.sh           # every suite
#     dev/litmus.sh props     # some of them
#
# It builds litmus once into target/litmus from the release tarball, which
# needs a C compiler (the Xcode command-line tools on macOS), and starts
# the server with clojure -M:litmus.
set -euo pipefail

version=0.18
sha256=efb3a1b91472760f66f2fcb4ca2b58af19c024fb0263ca725f1f1b44182fd8ca
port=${LITMUS_PORT:-8765}
url="http://127.0.0.1:$port/dav/"
dir=target/litmus
suites=("${@:-basic copymove props http locks}")

cd "$(dirname "$0")/.."

if [ ! -x "$dir/litmus-$version/props" ]; then
  mkdir -p "$dir"
  curl -sSfL -o "$dir/litmus-$version.tar.gz" \
    "https://notroj.github.io/litmus/litmus-$version.tar.gz"
  echo "$sha256  $dir/litmus-$version.tar.gz" | shasum -a 256 -c -
  tar -xzf "$dir/litmus-$version.tar.gz" -C "$dir"
  (cd "$dir/litmus-$version" &&
     ./configure --with-included-neon --without-ssl > configure.log 2>&1 &&
     make > make.log 2>&1)
fi

clojure -M:litmus "$port" > "$dir/server.log" 2>&1 &
server=$!
trap 'kill $server 2> /dev/null' EXIT

for _ in $(seq 1 120); do
  curl -sf -o /dev/null -X OPTIONS "$url" && break
  sleep 1
done

failed=0
for suite in ${suites[*]}; do
  (cd "$dir/litmus-$version" && "./$suite" -n "$url") || failed=1
done
exit $failed
