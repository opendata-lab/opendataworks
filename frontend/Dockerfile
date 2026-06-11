# 前端构建镜像
FROM node:20.19-alpine AS builder

WORKDIR /app

ARG VITE_DATAAGENT_WIDGET_JS_URL=/dataagent/widget/opendataworks-widget.bundle.js
ENV VITE_DATAAGENT_WIDGET_JS_URL=${VITE_DATAAGENT_WIDGET_JS_URL}

# 复制 package 文件
COPY package*.json ./

# 安装依赖
RUN npm install

# 复制源代码
COPY . .

# 构建生产版本
RUN npm run build

# 生产运行镜像 - 使用 nginx 提供静态文件
FROM nginx:alpine

# 复制构建产物到 nginx
COPY --from=builder /app/dist /usr/share/nginx/html

# 复制 nginx 配置
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
