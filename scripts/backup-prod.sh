#!/usr/bin/env bash
# 生产数据备份：pgvector SQL dump + Redis RDB 快照 + 上传目录归档。
#
# 用法（在服务器上，compose 栈运行时执行）：
#   bash scripts/backup-prod.sh [备份目录] [保留天数]
#   默认备份到 ./backups，保留 7 天。
#
# 恢复脚本见 scripts/restore-prod.sh。
set -euo pipefail

BACKUP_DIR="${1:-./backups}"
KEEP_DAYS="${2:-7}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/$STAMP"
PROJECT="${COMPOSE_PROJECT_NAME:-lc4j-prod}"

mkdir -p "$OUT"
echo "[backup] -> $OUT"

# 1) PostgreSQL：pg_dump 完整逻辑备份（自定义格式，含 schema+数据，经 stdout 流式）
if docker compose -p "$PROJECT" ps pgvector >/dev/null 2>&1; then
  echo "[backup] dumping pgvector..."
  docker compose -p "$PROJECT" exec -T pgvector pg_dump -U postgres -d vectordb -Fc > "$OUT/pgvector.dump"
else
  echo "[backup] WARN: pgvector not running, skipped" >&2
fi

# 2) Redis：BGSAVE 后拷贝 RDB 快照
if docker compose -p "$PROJECT" ps redis >/dev/null 2>&1; then
  echo "[backup] saving redis..."
  docker compose -p "$PROJECT" exec -T redis redis-cli --no-auth-warning BGSAVE >/dev/null 2>&1 || true
  sleep 1
  docker cp "${PROJECT}-redis-1":/data/dump.rdb "$OUT/redis.rdb" 2>/dev/null || echo "[backup] WARN: redis rdb copy failed, skipped"
fi

# 3) 上传目录归档
for d in cityengine-workspace geoscene-upload-temp; do
  if [ -d "./$d" ] && [ -n "$(ls -A "./$d" 2>/dev/null)" ]; then
    echo "[backup] archiving ./$d ..."
    tar czf "$OUT/$d.tgz" -C . "$d"
  fi
done

# 4) 清理过期备份
find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -mtime +"$KEEP_DAYS" -exec rm -rf {} + 2>/dev/null || true

echo "[backup] done. Contents:"
ls -lh "$OUT"
echo "[backup] total backups: $(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d | wc -l) (keep $KEEP_DAYS days)"
