#!/usr/bin/env bash
# Record real answers of Nextcloud and Apache's mod_dav for the tests, in
# test/resources/webdav, from containers on localhost.
#
#     dev/record.sh          # start the containers, record, and stop them
#     KEEP=1 dev/record.sh   # leave the containers running afterwards
#
# It needs Docker. The Nextcloud account is a throwaway one of the
# container's own, which only localhost reaches.
set -euo pipefail

nextcloud_image=nextcloud:34.0.4-apache
apache_image=httpd:2.4.69
export NEXTCLOUD_URL=http://localhost:8781
export NEXTCLOUD_USER=simon
export NEXTCLOUD_PASSWORD=203e9059023ed9f21d81e1292cf71d85
export APACHE_URL=http://localhost:8782

cd "$(dirname "$0")/.."

stop() {
  docker rm -f webdav-clj-nextcloud webdav-clj-apache > /dev/null 2>&1 || true
}

if [ -z "${KEEP:-}" ]; then
  trap stop EXIT
fi

if [ -z "$(docker ps -q -f name=webdav-clj-nextcloud)" ]; then
  docker run -d --name webdav-clj-nextcloud -p 127.0.0.1:8781:80 \
    -e SQLITE_DATABASE=nextcloud \
    -e NEXTCLOUD_ADMIN_USER="$NEXTCLOUD_USER" \
    -e NEXTCLOUD_ADMIN_PASSWORD="$NEXTCLOUD_PASSWORD" \
    -e NEXTCLOUD_TRUSTED_DOMAINS=localhost \
    "$nextcloud_image" > /dev/null
fi

if [ -z "$(docker ps -q -f name=webdav-clj-apache)" ]; then
  docker run -d --name webdav-clj-apache -p 127.0.0.1:8782:80 \
    -v "$PWD/dev/apache/webdav-clj.conf:/usr/local/apache2/conf/extra/webdav-clj.conf:ro" \
    "$apache_image" sh -c '
      mkdir -p dav var && chown www-data:www-data dav var &&
      echo "Include conf/extra/webdav-clj.conf" >> conf/httpd.conf &&
      exec httpd-foreground' > /dev/null
fi

# Nextcloud installs itself on its first start, which takes a minute or two
for _ in $(seq 1 180); do
  curl -sf "$NEXTCLOUD_URL/status.php" | grep -q '"installed":true' &&
    curl -sf -o /dev/null -X OPTIONS "$APACHE_URL/dav/" && break
  sleep 2
done

clojure -M:record
