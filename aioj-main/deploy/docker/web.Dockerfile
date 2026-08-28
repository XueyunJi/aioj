FROM node:24-alpine@sha256:2a49bdf71e9fd965a58c1703fd9ddd205b34e5782b692a72dd1d248abb0beb43 AS build
ARG APP_WORKSPACE
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
WORKDIR /workspace
COPY package.json package-lock.json* ./
COPY apps ./apps
COPY packages ./packages
COPY tsconfig.base.json ./
RUN npm ci --include=optional
RUN npm run build -w ${APP_WORKSPACE}

FROM nginx:1.27-alpine@sha256:62223d644fa234c3a1cc785ee14242ec47a77364226f1c811d2f669f96dc2ac8
ARG APP_DIR
COPY --from=build /workspace/apps/${APP_DIR}/dist /usr/share/nginx/html
COPY deploy/nginx/spa.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
