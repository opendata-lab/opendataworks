#!/bin/bash

# DolphinScheduler集成测试环境准备脚本

set -e

echo "========================================="
echo "  DolphinScheduler 集成测试环境准备"
echo "========================================="
echo ""

# 配置变量
DOLPHIN_URL="http://localhost:12345/dolphinscheduler"
USERNAME="admin"
PASSWORD="dolphinscheduler123"
PROJECT_NAME="opendataworks-test"
WORKFLOW_NAME="opendataworks-test-workflow"

echo "1. 检查DolphinScheduler服务..."
if curl -s -f "$DOLPHIN_URL/login" > /dev/null; then
    echo "✓ DolphinScheduler服务可访问"
else
    echo "✗ 无法访问DolphinScheduler服务: $DOLPHIN_URL"
    echo "  请先启动DolphinScheduler服务"
    exit 1
fi
echo ""

echo "2. 登录DolphinScheduler..."
LOGIN_RESPONSE=$(curl -s -X POST "$DOLPHIN_URL/login?userName=$USERNAME&userPassword=$PASSWORD")
SESSION_ID=$(echo $LOGIN_RESPONSE | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)

if [ -z "$SESSION_ID" ]; then
    echo "✗ 登录失败"
    echo "  响应: $LOGIN_RESPONSE"
    exit 1
fi

echo "✓ 登录成功, SessionID: ${SESSION_ID:0:20}..."
echo ""

echo "3. 获取项目列表..."
PROJECTS_RESPONSE=$(curl -s -X GET "$DOLPHIN_URL/projects" \
    -H "Cookie: sessionId=$SESSION_ID")

echo "  项目列表: $PROJECTS_RESPONSE"
echo ""

echo "========================================="
echo "准备工作完成！"
echo ""
echo "下一步操作："
echo "1. 通过UI创建测试项目和工作流："
echo "   - 访问: http://localhost:12345/dolphinscheduler/ui"
echo "   - 登录: admin/dolphinscheduler123"
echo "   - 创建项目: $PROJECT_NAME"
echo "   - 创建工作流: $WORKFLOW_NAME"
echo ""
echo "2. 记录项目编码(project-code)和工作流编码(workflow-code)"
echo ""
echo "3. 导出环境变量(用于测试运行)："
echo "   export DS_BASE_URL=$DOLPHIN_URL"
echo "   export DS_TOKEN=\"...\" (使用上方获取的Token)"
echo "   export DS_PROJECT_NAME=$PROJECT_NAME"
echo ""
echo "4. 运行测试："
echo "   cd backend"
echo "   backend/mvnw -f backend/pom.xml test -Dtest=RealDolphinSchedulerIntegrationTest"
echo "========================================="
