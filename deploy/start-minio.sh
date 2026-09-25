#!/bin/bash
# 启动本机 MinIO(官方已停止 darwin-arm64 构建,用 Rosetta 转译 amd64 二进制)
set -e
export MINIO_ROOT_USER=${MINIO_ROOT_USER:-minioadmin}
export MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD:-minioadmin}
DATA_DIR="${HOME}/toys-minio-data"
mkdir -p "$DATA_DIR"
exec minio server "$DATA_DIR" --console-address ":9001"
