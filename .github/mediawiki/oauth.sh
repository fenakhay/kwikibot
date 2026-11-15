#!/usr/bin/env bash

set -euo pipefail

name="kwikibot-mediawiki"
port="${1:-8080}"
here="$(cd "$(dirname "$0")" && pwd)"
version=$(curl --silent --fail "http://localhost:$port/api.php?action=query&meta=siteinfo&format=json&formatversion=2" \
    | sed -n 's/.*"generator":"MediaWiki \([0-9]*\)\.\([0-9]*\).*/\1_\2/p')
branch="REL$version"

docker cp "$here/createOwnerOnlyConsumer.php" "$name:/tmp/createOwnerOnlyConsumer.php"
docker exec --interactive "$name" bash -s -- "$branch" <<'INSIDE' >&2
set -euo pipefail
branch="$1"
cd /var/www/html
if [ ! -d extensions/OAuth ]; then
    git clone --quiet --depth 1 --branch "$branch" https://gerrit.wikimedia.org/r/mediawiki/extensions/OAuth extensions/OAuth
    curl --silent --show-error --fail --location https://getcomposer.org/download/latest-stable/composer.phar \
        --output /tmp/composer.phar
    (cd extensions/OAuth && COMPOSER_ALLOW_SUPERUSER=1 php /tmp/composer.phar install --quiet --no-dev \
        --no-interaction --prefer-source)
    echo "wfLoadExtension( 'OAuth' );" >> LocalSettings.php
    for right in mwoauthproposeconsumer mwoauthmanageconsumer mwoauthupdateownconsumer; do
        echo "\$wgGroupPermissions['sysop']['$right'] = true;" >> LocalSettings.php
    done
    php maintenance/run.php update --quick >/dev/null
    php maintenance/run.php resetUserEmail --no-reset-password Admin admin@example.org >/dev/null
    chown -R www-data:www-data /var/www/data
fi
INSIDE

docker exec "$name" apache2ctl -k graceful >/dev/null 2>&1
for _ in $(seq 30); do
    curl --silent --fail "http://localhost:$port/api.php?action=query&meta=siteinfo&siprop=extensions&format=json" \
        | grep -q '"name":"OAuth"' && break
    sleep 1
done

consumer=$(docker exec --env MW_INSTALL_PATH=/var/www/html "$name" php /tmp/createOwnerOnlyConsumer.php \
    --user Admin --grants basic --grants highvolume --grants editpage --grants createeditmovepage \
    --grants uploadfile --grants uploadeditmovefile)
docker exec "$name" chown -R www-data:www-data /var/www/data

echo "$consumer" | python3 -c '
import json, sys
consumer = json.loads(sys.stdin.read())
for name, key in [("CONSUMER_KEY", "consumerKey"), ("CONSUMER_SECRET", "consumerSecret"),
                  ("ACCESS_TOKEN", "accessToken"), ("ACCESS_SECRET", "accessSecret")]:
    print(f"export KWIKI_MEDIAWIKI_OAUTH_{name}={consumer[key]}")
'
