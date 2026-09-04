FROM node:20-alpine AS build

RUN corepack enable && corepack prepare pnpm@9.15.9 --activate
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .

ARG VITE_AUTH_MODE=dev
ARG VITE_CASDOOR_SERVER_URL=http://localhost:8000
ARG VITE_CASDOOR_CLIENT_ID=
ARG VITE_CASDOOR_ORGANIZATION=benefit-center
ARG VITE_CASDOOR_APP_NAME=rag-shared
ARG VITE_CASDOOR_SCOPE="openid profile offline_access"
ENV VITE_AUTH_MODE=$VITE_AUTH_MODE \
    VITE_CASDOOR_SERVER_URL=$VITE_CASDOOR_SERVER_URL \
    VITE_CASDOOR_CLIENT_ID=$VITE_CASDOOR_CLIENT_ID \
    VITE_CASDOOR_ORGANIZATION=$VITE_CASDOOR_ORGANIZATION \
    VITE_CASDOOR_APP_NAME=$VITE_CASDOOR_APP_NAME \
    VITE_CASDOOR_SCOPE=$VITE_CASDOOR_SCOPE
RUN pnpm build

FROM nginx:1.27-alpine
RUN apk add --no-cache wget

ENV BENEFIT_API_URL=http://host.docker.internal:8183 \
    NGINX_ENVSUBST_FILTER=BENEFIT_API_URL

COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8083
