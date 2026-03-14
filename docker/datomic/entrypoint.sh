#!/bin/sh
set -e
envsubst < /opt/datomic/config/transactor.properties > /tmp/transactor.properties
exec /opt/datomic/bin/transactor /tmp/transactor.properties
