#!/usr/bin/env bash
# 生产数据恢复：从 backup-prod.sh 生成的备份目录恢复 pgvector + Redis + 上传目录。
#
# 用法（服务器上，compose 栈已启动后）：
#   bash scripts/restore-prod.sh backups/20260807-000000
#
# 注意：
#   - pgvector 恢复会先 DROP 并重建库，目标库中现有数据将被覆盖；
#   - 仅恢复逻辑备份，不保证业务一致性（建议停服或选低峰期执行）。
set -euo pipefail

BACKUP="$1"
PROJECT="${COMPOSE_PROJECT_NAME:-lc4j-prod}"

if [ ! -d "$BACKUP" ]; then
  echo "usage: bash scripts/restore-prod.sh <backup-dir>" >&2
  exit 1
fi
echo "[restore] from $BACKUP"

# 1) PostgreSQL
if [ -f "$BACKUP/pgvector.dump" ]; then
  echo "[restore] restoring pgvector..."
  docker compose -p "$PROJECT" exec -T pgvector sh -c \
    'psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS vectordb" -c "CREATE DATABASE vectordb"' >/dev/null
  docker compose -p "$PROJECT" exec -T pgvector pg_restore -U postgres -d vectordb < "$BACKUP/pgvector.dump"
else
  echo "[restore] WARN: no pgvector.dump found, skipped" >&2
fi

# 2) Redis
if [ -f "$BACKUP/redis.rdb" ]; then
  echo "[restore] restoring redis (copy rdb + restart)..."
  docker cp "$BACKUP/redis.rdb" "${PROJECT}-redis-1":/data/dump.rdb
  docker compose -p "$PROJECT" restart redis
else
  echo "[restore] WARN: no redis.rdb found, skipped" >&2
fi

# 3) 上传目录
for d in cityengine-workspace geoscene-upload-temp; do
  if [ -f "$BACKUP/$d.tgz" ]; then
    echo "[restore] extracting ./$d ..."
    tar xzf "$BACKUP/$d.tgz" -C .
  fi
done

echo "[restore] done. 建议重启相关服务：docker compose -f compose-prod.yaml restart backend python-gis"
