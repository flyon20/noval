FROM node:22-alpine AS frontend-build

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine

COPY docker/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=frontend-build /workspace/frontend/dist /usr/share/nginx/html
