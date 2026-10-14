#!/bin/bash
# Runs once, when the Postgres data volume is first created.
#
# Database-per-service, enforced with credentials rather than convention: every service gets
# its own database AND its own login that owns only that database, so no service can read or
# write another's tables even by accident.
#
# Runs in a subshell because the Postgres entrypoint *sources* init scripts that aren't
# executable (e.g. after a Windows checkout); strict-mode flags must not leak into it.
(
  set -euo pipefail

  for service in auth product order payment notification; do
    user="${service}_service"
    database="${service}db"
    password_var="$(echo "${service}" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
    password="${!password_var:-${user}}"

    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres <<SQL
CREATE USER ${user} WITH PASSWORD '${password}';
CREATE DATABASE ${database} OWNER ${user};
REVOKE ALL ON DATABASE ${database} FROM PUBLIC;
SQL
    echo "created database ${database} owned by ${user}"
  done
)
