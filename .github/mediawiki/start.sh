#!/usr/bin/env bash
#
# Starts a throwaway MediaWiki in Docker, for LocalWikiTest to run against.
#
#   .github/mediawiki/start.sh [image tag] [port]
#   KWIKI_MEDIAWIKI=http://localhost:8080 ./gradlew :kwikibot-client:liveTest --tests '*LocalWikiTest'
#   docker rm -f kwikibot-mediawiki
#
# The tag is one of the official image's: 1.39, lts, latest. The wiki is installed on SQLite with an
# account, Admin, that the tests log in to through a bot password, and with AbuseFilter enabled where the
# release bundles it. Everything here is test data for a container that is thrown away.

set -euo pipefail

tag="${1:-lts}"
port="${2:-8080}"
name="kwikibot-mediawiki"
server="http://localhost:$port"

admin="Admin"
admin_password="kwikibot-admin-password"
# createBotPassword.php refuses a bot password shorter than 32 characters. LocalWikiTest defaults to the
# same one.
bot_name="kwikibot"
bot_password="${KWIKI_MEDIAWIKI_BOT_PASSWORD:-kwikibotkwikibotkwikibotkwikibot}"
no_mail_bot="nomail"
no_mail_password="nomailnomailnomailnomailnomailno"
mailbox="Mailbox"
mailbox_password="kwikibot-mailbox-password"

echo "Starting mediawiki:$tag on $server"
docker rm -f "$name" >/dev/null 2>&1 || true
docker run --detach --name "$name" --publish "$port:80" "mediawiki:$tag" >/dev/null

for _ in $(seq 60); do
    curl --silent --fail --output /dev/null "$server/" && break
    sleep 1
done

# Inside the container: install, then give the web server the database the installer created as root.
# maintenance/run.php arrived in 1.40; older releases run each script directly.
docker exec --interactive "$name" bash -s -- "$server" "$admin" "$admin_password" "$bot_name" "$bot_password" \
    "$mailbox" "$mailbox_password" "$no_mail_bot" "$no_mail_password" <<'INSIDE'
set -euo pipefail
server="$1" admin="$2" admin_password="$3" bot_name="$4" bot_password="$5" mailbox="$6" mailbox_password="$7"
no_mail_bot="$8" no_mail_password="$9"

maintenance() {
    if [ -f maintenance/run.php ]; then php maintenance/run.php "$@"; else php "maintenance/$1.php" "${@:2}"; fi
}

maintenance install --dbtype=sqlite --dbpath=/var/www/data --dbname=kwikibot --server="$server" \
    --scriptpath="" --lang=en --pass="$admin_password" "kwikibot test wiki" "$admin" >/dev/null

echo '$wgEnableUploads = true;' >> LocalSettings.php
echo '$wgAllowCopyUploads = true;' >> LocalSettings.php
echo '$wgCopyUploadsDomains = [ "localhost" ];' >> LocalSettings.php
echo "\$wgGroupPermissions['sysop']['upload_by_url'] = true;" >> LocalSettings.php
chown -R www-data:www-data images

if [ -d extensions/AbuseFilter ]; then
    echo "wfLoadExtension( 'AbuseFilter' );" >> LocalSettings.php
    maintenance update --quick >/dev/null
fi

maintenance createBotPassword --appid="$bot_name" \
    --grants=basic,highvolume,editpage,createeditmovepage,patrol,viewmywatchlist,uploadfile,uploadeditmovefile,editmyoptions,sendemail "$admin" "$bot_password" >/dev/null

maintenance createBotPassword --appid="$no_mail_bot" \
    --grants=basic,highvolume,editpage "$admin" "$no_mail_password" >/dev/null
maintenance createAndPromote "$mailbox" "$mailbox_password" >/dev/null
maintenance resetUserEmail --no-reset-password "$admin" admin@example.org >/dev/null
maintenance resetUserEmail --no-reset-password "$mailbox" mailbox@example.org >/dev/null
echo '$wgPasswordSender = "wiki@example.org";' >> LocalSettings.php
echo "sendmail_path = \"/bin/sh -c 'cat >> /tmp/kwikibot-mail'\"" > /usr/local/etc/php/conf.d/kwikibot-mail.ini

chown -R www-data:www-data /var/www/data
INSIDE

docker exec "$name" apache2ctl -k graceful >/dev/null 2>&1

for _ in $(seq 30); do
    if curl --silent --fail "$server/api.php?action=query&meta=siteinfo&format=json" | grep -q generator; then
        version=$(curl --silent "$server/api.php?action=query&meta=siteinfo&format=json&formatversion=2" \
            | sed -n 's/.*"generator":"\([^"]*\)".*/\1/p')
        echo "Ready: $version at $server, logging in as $admin@$bot_name"
        exit 0
    fi
    sleep 1
done

echo "MediaWiki did not come up at $server" >&2
docker logs "$name" >&2
exit 1
