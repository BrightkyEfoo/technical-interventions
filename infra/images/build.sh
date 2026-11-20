#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/../.."
tag=${SERVICEOPS_TAG:-local}
for variant in java kotlin; do
  for module in "serviceops-$variant" "serviceops-notifications-$variant"; do
    docker build -f infra/images/jvm.Dockerfile --build-arg MODULE="backends/$variant/$module" \
      -t "$module:$tag" .
  done
done
for client in web mobile; do
  docker build -f infra/images/client.Dockerfile --build-arg CLIENT="$client" -t "serviceops-$client:$tag" .
done
