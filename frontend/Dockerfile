# Static files are the same on every architecture, so the bundle is built once on the build machine's own platform.
FROM --platform=$BUILDPLATFORM node:20-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Optional cloud sticker import. Vite inlines these at build time; they're public client ids, not secrets.
ARG VITE_GOOGLE_CLIENT_ID=
ARG VITE_GOOGLE_API_KEY=
ARG VITE_ONEDRIVE_CLIENT_ID=
RUN npm run build

FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
EXPOSE 8080 80 443
