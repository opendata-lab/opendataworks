---
layout: page
title: 一站式智能数据平台
titleTemplate: OpenDataWorks
footer: false
---

<script setup>
import { ref } from 'vue'

const activeTab = ref('lineage')
const tabs = [
  { id: 'lineage', label: '数据血缘', img: '/readme-lineage.png' },
  { id: 'schedule', label: '任务调度', img: '/readme-workflows.png' },
  { id: 'asset', label: '数据资产', img: '/readme-datastudio.png' },
  { id: 'query', label: '智能问数', img: '' },
]

const activeTerminalTab = ref('docker')
const copied = ref(false)

const copyTerminalCommand = () => {
  const textToCopy = activeTerminalTab.value === 'docker'
    ? 'git clone https://github.com/opendata-lab/opendataworks.git && cd opendataworks && cp deploy/.env.example deploy/.env && docker compose -f deploy/docker-compose.dev.yml up -d'
    : 'git clone https://github.com/opendata-lab/opendataworks.git && cd opendataworks && cp deploy/.env.example deploy/.env && docker compose -f deploy/docker-compose.prod.yml up -d'

  navigator.clipboard.writeText(textToCopy).then(() => {
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  })
}
</script>

<div class="landing">
  <!-- Hero Section -->
  <section class="hero-section">
    <div class="hero-glow"></div>
    <p class="hero-badge">Open Source Data Platform</p>
    <h1 class="hero-title">一站式智能数据工作台<br><span class="hero-title-gradient">OpenDataWorks</span></h1>
    <p class="hero-subtitle">把<strong>元数据管理、工作流编排、数据血缘</strong>与内置的 <strong>NL2SQL 智能问数</strong>整合进同一个开源、可部署的平台。让技术与业务在同一处理解数据、使用数据。</p>
    <div class="hero-actions">
      <a href="/guide/quick-start" class="btn-primary">快速开始</a>
      <a href="https://opendataworks-demo.vercel.app/" target="_blank" class="btn-secondary">在线 Demo</a>
      <a href="https://github.com/opendata-lab/opendataworks" target="_blank" class="btn-secondary">GitHub ⭐</a>
    </div>
    <!-- Stats strip -->
    <div class="hero-stats">
      <div class="stat-item"><span class="stat-num">4</span><span class="stat-label">核心模块</span></div>
      <div class="stat-divider"></div>
      <div class="stat-item"><span class="stat-num">NL2SQL</span><span class="stat-label">内置智能问数</span></div>
      <div class="stat-divider"></div>
      <div class="stat-item"><span class="stat-num">1</span><span class="stat-label">命令一键部署</span></div>
      <div class="stat-divider"></div>
      <div class="stat-item"><span class="stat-num">GPL-3.0</span><span class="stat-label">完全开源</span></div>
    </div>
    <!-- Architecture SVG -->
    <div class="arch-visual">
      <svg viewBox="0 0 600 280" fill="none" xmlns="http://www.w3.org/2000/svg" style="width:100%;">
        <!-- Top modules -->
        <rect x="20" y="20" width="110" height="48" rx="10" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.5"/>
        <text x="75" y="49" text-anchor="middle" fill="#334155" font-size="12" font-weight="500">数据资产</text>
        <rect x="150" y="20" width="110" height="48" rx="10" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.5"/>
        <text x="205" y="49" text-anchor="middle" fill="#334155" font-size="12" font-weight="500">任务调度</text>
        <rect x="280" y="20" width="110" height="48" rx="10" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.5"/>
        <text x="335" y="49" text-anchor="middle" fill="#334155" font-size="12" font-weight="500">数据血缘</text>
        <rect x="410" y="20" width="110" height="48" rx="10" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.5"/>
        <text x="465" y="49" text-anchor="middle" fill="#334155" font-size="12" font-weight="500">数据治理</text>
        <rect x="532" y="20" width="50" height="48" rx="10" fill="var(--vp-c-brand-soft)" stroke="var(--vp-c-brand-1)" stroke-width="1.5"/>
        <text x="557" y="49" text-anchor="middle" fill="var(--vp-c-brand-1)" font-size="12" font-weight="600">AI</text>
        <!-- Platform layer -->
        <rect x="80" y="110" width="440" height="55" rx="12" fill="var(--vp-c-brand-soft)" stroke="var(--vp-c-brand-1)" stroke-width="1.5"/>
        <text x="300" y="143" text-anchor="middle" fill="var(--vp-c-brand-2)" font-size="14" font-weight="600">OpenDataWorks Platform Core</text>
        <!-- Bottom infra -->
        <rect x="100" y="210" width="100" height="42" rx="8" fill="#f8fafc" stroke="#e2e8f0" stroke-width="1"/>
        <text x="150" y="236" text-anchor="middle" fill="#64748b" font-size="11" font-weight="500">MySQL</text>
        <rect x="230" y="210" width="140" height="42" rx="8" fill="#f8fafc" stroke="#e2e8f0" stroke-width="1"/>
        <text x="300" y="236" text-anchor="middle" fill="#64748b" font-size="11" font-weight="500">DolphinScheduler</text>
        <rect x="400" y="210" width="100" height="42" rx="8" fill="#f8fafc" stroke="#e2e8f0" stroke-width="1"/>
        <text x="450" y="236" text-anchor="middle" fill="#64748b" font-size="11" font-weight="500">Redis</text>
        <!-- Connections top to platform -->
        <line x1="75" y1="68" x2="150" y2="110" stroke="#10b981" stroke-width="1.2" class="flow-line"/>
        <line x1="205" y1="68" x2="230" y2="110" stroke="#10b981" stroke-width="1.2" class="flow-line"/>
        <line x1="335" y1="68" x2="330" y2="110" stroke="#10b981" stroke-width="1.2" class="flow-line"/>
        <line x1="465" y1="68" x2="420" y2="110" stroke="#10b981" stroke-width="1.2" class="flow-line"/>
        <line x1="557" y1="68" x2="490" y2="110" stroke="var(--vp-c-brand-1)" stroke-width="1.2" class="flow-line"/>
        <!-- Connections platform to infra -->
        <line x1="200" y1="165" x2="150" y2="210" stroke="#cbd5e1" stroke-width="1.2"/>
        <line x1="300" y1="165" x2="300" y2="210" stroke="#cbd5e1" stroke-width="1.2"/>
        <line x1="400" y1="165" x2="450" y2="210" stroke="#cbd5e1" stroke-width="1.2"/>
      </svg>
    </div>
  </section>

  <!-- Features Section -->
  <section class="features-section">
    <p class="section-eyebrow">CORE CAPABILITIES</p>
    <h2 class="features-title">全域数据管理解决方案</h2>
    <p class="features-subtitle">精心打磨的开源组件，覆盖数据平台从资产、调度、血缘到智能问数的完整生命周期。</p>
    <div class="features-grid">
      <div class="feature-card">
        <div class="feature-icon">📊</div>
        <h3>数据资产</h3>
        <p>ODS 至 ADS 全流程模型管理，可视化建表与物理 DDL 同步，软删除回收站与存储/热度分析。</p>
      </div>
      <div class="feature-card">
        <div class="feature-icon">⚡</div>
        <h3>工作流编排</h3>
        <p>集成 DolphinScheduler，可视化 DAG 依赖编排、版本比对回滚、发布审批与历史补数据。</p>
      </div>
      <div class="feature-card">
        <div class="feature-icon">🔗</div>
        <h3>全域数据血缘</h3>
        <p>基于 SQL 自动解析表级血缘，力导向拓扑大图展示，支持上下游穿透与跨库依赖追溯。</p>
      </div>
      <div class="feature-card">
        <div class="feature-icon">🤖</div>
        <h3>智能问数</h3>
        <p>内置 AI Agent，中文自然语言秒级转结构化 SQL，自愈式纠错并自动推荐统计图表。</p>
      </div>
      <div class="feature-card">
        <div class="feature-icon">🛡️</div>
        <h3>数据治理</h3>
        <p>数据全周期监控，包含执行质量追踪、超期与空表校验告警，闲置资产预警。</p>
      </div>
      <div class="feature-card">
        <div class="feature-icon">🚀</div>
        <h3>一键部署</h3>
        <p>前端、后端、DataAgent、MySQL、Redis 与 Portal MCP 全部容器化，Docker Compose 即开即用。</p>
      </div>
    </div>
  </section>

  <!-- Product Showcase Section -->
  <section class="showcase-section">
    <div class="showcase-inner">
      <p class="section-eyebrow">PRODUCT TOUR</p>
      <h2 class="showcase-title">直观的操作界面</h2>
      <p class="showcase-desc">点击切换预览真实的操作面板和可视化界面</p>
      <div class="showcase-tabs">
        <button v-for="tab in tabs" :key="tab.id" class="tab-btn" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}</button>
      </div>
      <!-- macOS Window Wrapper -->
      <div class="showcase-window">
        <div class="window-titlebar">
          <div class="window-dots">
            <span class="dot dot-red"></span>
            <span class="dot dot-yellow"></span>
            <span class="dot dot-green"></span>
          </div>
          <span class="window-title">OpenDataWorks Console</span>
        </div>
        <div class="showcase-content">
          <template v-for="tab in tabs" :key="tab.id">
            <template v-if="activeTab === tab.id">
              <img v-if="tab.img" :src="tab.img" :alt="tab.label" />
              <!-- Simulated Interactive AI Chat for the 'query' tab -->
              <div v-else class="chat-workspace">
                <div class="chat-bubble chat-bubble-user">
                  <div class="chat-avatar chat-avatar-user">U</div>
                  <div class="chat-bubble-content">最近30天每天工作流发布次数趋势</div>
                </div>
                <div class="chat-bubble chat-bubble-ai">
                  <div class="chat-avatar chat-avatar-ai">AI</div>
                  <div class="chat-bubble-content">
                    <span>为您生成以下 SQL 查询，并提取了数据统计图表：</span>
                    <div class="chat-sql-block">
                      <span class="keyword">SELECT</span> <span class="function">date</span>(create_time) <span class="keyword">as</span> date, <span class="function">count</span>(<span class="number">1</span>) <span class="keyword">as</span> total<br>
                      <span class="keyword">FROM</span> opendataworks.workflow_task_instance<br>
                      <span class="keyword">WHERE</span> create_time &gt;= <span class="function">date_sub</span>(<span class="function">now</span>(), <span class="keyword">interval</span> <span class="number">30</span> <span class="keyword">day</span>)<br>
                      <span class="keyword">GROUP BY</span> <span class="number">1</span> <span class="keyword">ORDER BY</span> <span class="number">1</span>;
                    </div>
                    <!-- Inline SVG Bar Chart -->
                    <div class="chat-chart-block">
                      <div class="chat-chart-title">工作流发布趋势 (过去30天)</div>
                      <svg viewBox="0 0 460 120" width="100%" height="120" xmlns="http://www.w3.org/2000/svg">
                        <!-- Grid lines -->
                        <line x1="40" y1="20" x2="440" y2="20" stroke="#1e293b" stroke-width="1"/>
                        <line x1="40" y1="50" x2="440" y2="50" stroke="#1e293b" stroke-width="1"/>
                        <line x1="40" y1="80" x2="440" y2="80" stroke="#1e293b" stroke-width="1"/>
                        <line x1="40" y1="100" x2="440" y2="100" stroke="#334155" stroke-width="1"/>
                        <!-- Bars -->
                        <rect x="60" y="60" width="20" height="40" rx="3" fill="#10b981" opacity="0.8"/>
                        <rect x="110" y="40" width="20" height="60" rx="3" fill="#10b981" opacity="0.8"/>
                        <rect x="160" y="70" width="20" height="30" rx="3" fill="#10b981" opacity="0.8"/>
                        <rect x="210" y="30" width="20" height="70" rx="3" fill="#10b981"/>
                        <rect x="260" y="45" width="20" height="55" rx="3" fill="#10b981" opacity="0.8"/>
                        <rect x="310" y="80" width="20" height="20" rx="3" fill="#10b981" opacity="0.6"/>
                        <rect x="360" y="50" width="20" height="50" rx="3" fill="#10b981" opacity="0.8"/>
                        <rect x="400" y="35" width="20" height="65" rx="3" fill="#10b981"/>
                        <!-- Axis Labels -->
                        <text x="70" y="114" fill="#64748b" font-size="8" text-anchor="middle">05-01</text>
                        <text x="170" y="114" fill="#64748b" font-size="8" text-anchor="middle">05-10</text>
                        <text x="270" y="114" fill="#64748b" font-size="8" text-anchor="middle">05-20</text>
                        <text x="370" y="114" fill="#64748b" font-size="8" text-anchor="middle">05-30</text>
                        <text x="35" y="23" fill="#64748b" font-size="8" text-anchor="end">80</text>
                        <text x="35" y="53" fill="#64748b" font-size="8" text-anchor="end">40</text>
                        <text x="35" y="83" fill="#64748b" font-size="8" text-anchor="end">20</text>
                      </svg>
                    </div>
                  </div>
                </div>
              </div>
            </template>
          </template>
        </div>
      </div>
      <a href="https://opendataworks-demo.vercel.app/" target="_blank" class="demo-link">
        立即体验在线 Demo 
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <line x1="7" y1="17" x2="17" y2="7"></line>
          <polyline points="7 7 17 7 17 17"></polyline>
        </svg>
      </a>
    </div>
  </section>

  <!-- Quickstart Terminal -->
  <section class="quickstart-section">
    <p class="section-eyebrow">GET STARTED</p>
    <h2 class="quickstart-title">三步快速启动</h2>
    <p class="quickstart-desc">所有组件均通过容器底座打包，使用 Docker Compose 快速初始化开发与生产环境。</p>
    <div class="terminal-window">
      <div class="terminal-header">
        <div class="terminal-tabs">
          <button class="terminal-tab" :class="{ active: activeTerminalTab === 'docker' }" @click="activeTerminalTab = 'docker'">开发环境</button>
          <button class="terminal-tab" :class="{ active: activeTerminalTab === 'npm' }" @click="activeTerminalTab = 'npm'">生产部署</button>
        </div>
        <button class="btn-copy" :class="{ copied: copied }" @click="copyTerminalCommand">
          <span v-if="!copied">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right: 4px; display: inline-block; vertical-align: middle;">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
            </svg>
            Copy
          </span>
          <span v-else>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" style="margin-right: 4px; display: inline-block; vertical-align: middle;">
              <polyline points="20 6 9 17 4 12"></polyline>
            </svg>
            Copied!
          </span>
        </button>
      </div>
      <div class="terminal-content">
        <template v-if="activeTerminalTab === 'docker'">
          <p><span class="comment"># 1. 克隆并进入项目根目录</span></p>
          <p><span class="cmd">git clone </span><span class="accent">https://github.com/opendata-lab/opendataworks.git</span><span class="cmd"> &amp;&amp; cd opendataworks</span></p>
          <p><span class="comment"># 2. 准备环境变量配置文件</span></p>
          <p><span class="cmd">cp deploy/.env.example deploy/.env</span></p>
          <p><span class="comment"># 3. 启动本地容器化开发堆栈</span></p>
          <p><span class="cmd">docker compose -f deploy/docker-compose.dev.yml up -d</span></p>
        </template>
        <template v-else>
          <p><span class="comment"># 1. 克隆并进入项目根目录</span></p>
          <p><span class="cmd">git clone </span><span class="accent">https://github.com/opendata-lab/opendataworks.git</span><span class="cmd"> &amp;&amp; cd opendataworks</span></p>
          <p><span class="comment"># 2. 准备生产配置（编辑 .env 填写密码、镜像 tag 与 Provider 凭据）</span></p>
          <p><span class="cmd">cp deploy/.env.example deploy/.env</span></p>
          <p><span class="comment"># 3. 启动生产容器堆栈</span></p>
          <p><span class="cmd">docker compose -f deploy/docker-compose.prod.yml up -d</span></p>
        </template>
      </div>
    </div>
    <p class="code-hint">启动完成后，在浏览器中访问 <span>http://localhost:8081</span> 即可登录使用。</p>
  </section>

  <!-- Built With (Tech Stack) -->
  <section class="tech-section">
    <p class="tech-label">Built With Modern Stack</p>
    <div class="tech-list">
      <!-- Vue -->
      <span class="tech-badge">
        <svg width="12" height="12" viewBox="0 0 256 221" fill="none" style="display:inline-block; margin-right:6px; vertical-align:middle;">
          <path d="M204.8 0H256L128 220.8L0 0h51.2L128 132.48L204.8 0z" fill="#41B883"/>
          <path d="M0 0h51.2L128 132.48L204.8 0h51.2L128 220.8L0 0z" fill="#41B883"/>
          <path d="M51.2 0h40.96l35.84 60.8L163.84 0h40.96L128 132.48L51.2 0z" fill="#35495E"/>
        </svg>
        Vue 3 (Vite)
      </span>
      <!-- Spring -->
      <span class="tech-badge">
        <svg width="12" height="12" viewBox="0 0 256 256" fill="none" style="display:inline-block; margin-right:6px; vertical-align:middle;">
          <path d="M128 0C57.3 0 0 57.3 0 128s57.3 128 128 128 128-57.3 128-128S198.7 0 128 0zm45.3 176c-8 8.8-19.3 15.3-33 15.3-17.6 0-29.3-11.2-29.3-29.6v-25c0-10 4-17 11.5-22.3l43.2-31c3.8-2.7 7.7-1 7.7 3.7v73.7c0 5.6 3.6 11.2 9.9 15.2z" fill="#6DB33F"/>
        </svg>
        Spring Boot
      </span>
      <!-- FastAPI / Python -->
      <span class="tech-badge">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; margin-right:6px; vertical-align:middle; color: #009688;">
          <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/>
        </svg>
        FastAPI (Python)
      </span>
      <!-- DS -->
      <span class="tech-badge">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; margin-right:6px; vertical-align:middle; color: #3b82f6;">
          <path d="M22 12h-4l-3 9L9 3l-3 9H2"/>
        </svg>
        DolphinScheduler
      </span>
      <!-- Element -->
      <span class="tech-badge">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; margin-right:6px; vertical-align:middle; color: #409EFF;">
          <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
          <line x1="9" y1="9" x2="15" y2="15"></line>
          <line x1="15" y1="9" x2="9" y2="15"></line>
        </svg>
        Element Plus
      </span>
      <!-- DB -->
      <span class="tech-badge">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; margin-right:6px; vertical-align:middle; color: #f59e0b;">
          <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
          <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
          <path d="M3 12c0 1.66 4 3 9 3s9-1.34 9-3"></path>
        </svg>
        MySQL &amp; Redis
      </span>
      <!-- Docker -->
      <span class="tech-badge">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; margin-right:6px; vertical-align:middle; color: #0db7ed;">
          <rect x="2" y="2" width="20" height="8" rx="2" ry="2"></rect>
          <rect x="2" y="14" width="20" height="8" rx="2" ry="2"></rect>
          <line x1="6" y1="6" x2="6.01" y2="6"></line>
          <line x1="6" y1="18" x2="6.01" y2="18"></line>
        </svg>
        Docker Compose
      </span>
    </div>
  </section>

  <!-- Final CTA Section -->
  <section class="cta-section">
    <div class="cta-glow"></div>
    <h2 class="cta-title">准备好整理你的数据资产了吗？</h2>
    <p class="cta-desc">现在就集成并开始构建你的数据治理流，提供完全开源免费的解决方案。</p>
    <div class="cta-actions">
      <a href="/guide/quick-start" class="btn-primary">快速开始</a>
      <a href="/guide/introduction" class="btn-secondary">了解更多</a>
      <a href="https://github.com/opendata-lab/opendataworks" target="_blank" class="btn-secondary">GitHub 仓库</a>
    </div>
  </section>

  <!-- Custom Footer -->
  <footer class="landing-footer">
    <div class="footer-grid">
      <div>
        <div class="footer-brand-logo">OpenData<span>Works</span></div>
        <p>一站式智能数据工作台，连接业务数据元模型、血缘、调度与智能问答。采用 GPL-3.0 开源协议发布。</p>
      </div>
      <div>
        <h4>产品服务</h4>
        <a href="/guide/quick-start">开发指南</a>
        <a href="https://opendataworks-demo.vercel.app/" target="_blank">在线 Demo</a>
        <a href="/changelog">更新日志</a>
      </div>
      <div>
        <h4>开源社区</h4>
        <a href="https://github.com/opendata-lab/opendataworks" target="_blank">GitHub</a>
        <a href="https://github.com/opendata-lab/opendataworks/issues" target="_blank">提交反馈 (Issues)</a>
        <a href="https://github.com/opendata-lab/opendataworks/discussions" target="_blank">社区讨论</a>
      </div>
      <div>
        <h4>快速指引</h4>
        <a href="/guide/quick-start">5分钟上手</a>
        <a href="/guide/deployment">环境部署</a>
        <a href="/guide/contribution">贡献指南</a>
      </div>
    </div>
    <div class="footer-bottom">
      <p>Copyright © 2024-present OpenDataWorks. All rights reserved.</p>
    </div>
  </footer>
</div>
