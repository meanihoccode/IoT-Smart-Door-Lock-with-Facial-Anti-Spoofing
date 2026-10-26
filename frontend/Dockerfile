# Static React build served by Caddy, which also terminates HTTPS and proxies /api to Core (same origin).
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2.8-alpine
COPY --from=build /src/dist /srv
