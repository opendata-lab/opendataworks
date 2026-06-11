FROM node:20.19-alpine AS builder

WORKDIR /app

COPY dataagent/dataagent-frontend/package*.json ./
RUN npm install

COPY dataagent/dataagent-frontend ./
RUN npm run build && npm run build:widget

FROM nginx:alpine

COPY --from=builder /app/dist /usr/share/nginx/html
COPY dataagent/dataagent-frontend/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
