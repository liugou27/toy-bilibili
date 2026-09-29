#!/usr/bin/env bash
# 把 deploy/nacos-configs/ 下的配置推送到 Nacos 配置中心(DataId=文件名,group=DEFAULT_GROUP)。
# 幂等:重复执行为覆盖发布。
# 用法:
#   ./deploy/nacos-init.sh                          # 默认 127.0.0.1:8848,serverIdentity 与 docker-compose 一致
#   NACOS_ADDR=10.0.0.5:8848 ./deploy/nacos-init.sh # 指定 Nacos 地址
# 鉴权说明:Nacos 3 admin API 需要身份。本仓库 docker-compose 以 NACOS_AUTH_ENABLED=false +
#   serverIdentity 头直通;若你的 Nacos 开启了账号鉴权,改用 accessToken:
#   TOKEN=$(curl -s -X POST "$ADDR/nacos/v1/auth/login" -d 'username=nacos&password=nacos' | jq -r .accessToken)
#   然后在下方 curl 里追加 -H "accessToken: $TOKEN"。
set -euo pipefail

ADDR="${NACOS_ADDR:-127.0.0.1:8848}"
IDENTITY_KEY="${NACOS_IDENTITY_KEY:-serverIdentity}"
IDENTITY_VALUE="${NACOS_IDENTITY_VALUE:-toys-video}"
DIR="$(cd "$(dirname "$0")/nacos-configs" && pwd)"

fail=0
for file in "$DIR"/*.yml; do
  data_id="$(basename "$file")"
  resp=$(curl -s -X POST "http://$ADDR/nacos/v3/admin/cs/config" \
    -H "$IDENTITY_KEY: $IDENTITY_VALUE" \
    --data-urlencode "dataId=$data_id" \
    --data-urlencode "groupName=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content@$file")
  if echo "$resp" | grep -q '"code":0'; then
    echo "published  $data_id"
  else
    echo "FAILED    $data_id -> $resp"
    fail=1
  fi
done
exit $fail
