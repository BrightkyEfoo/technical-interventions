FROM nginxinc/nginx-unprivileged:1.28-alpine
ARG CLIENT
COPY dist/${CLIENT}/browser/ /usr/share/nginx/html/
COPY infra/images/client.conf /etc/nginx/conf.d/default.conf
