# syntax=docker/dockerfile:1

FROM node:22-alpine AS builder
WORKDIR /app

COPY backend/package.json backend/package-lock.json ./
RUN npm ci --include=dev

COPY backend/tsconfig.json ./
COPY backend/src ./src
RUN npm run build

FROM node:22-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV PORT=8080

COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=builder /app/dist ./dist

RUN chown -R node:node /app
USER node

EXPOSE 8080

CMD ["npm", "start"]
