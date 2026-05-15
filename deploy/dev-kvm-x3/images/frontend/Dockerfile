FROM nginx:1.27-alpine

COPY deploy/dev-kvm-x3/images/frontend/navigator-container.conf /etc/nginx/conf.d/default.conf
COPY packages/navigator-frontend/dist /usr/share/nginx/html

EXPOSE 80
