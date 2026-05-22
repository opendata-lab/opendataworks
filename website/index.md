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
  { id: 'lineage', label: '数据血缘' },
  { id: 'schedule', label: '任务调度' },
  { id: 'asset', label: '数据资产' },
  { id: 'query', label: '智能查询' },
]
</script>

<style scoped>
.landing { font-family: 'Inter', system-ui, -apple-system, sans-serif; }

/* Hero */
.hero-section {
  text-align: center;
  padding: 5rem 2rem 4rem;
  max-width: 800px;
  margin: 0 auto;
}
.hero-badge {
  font-size: 0.8rem;
  color: #6b7280;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  margin-bottom: 1.5rem;
}
.hero-title {
  font-size: 3rem;
  font-weight: 800;
  color: #0f172a;
  margin: 0 0 1.5rem;
  line-height: 1.2;
}
.hero-subtitle {
  font-size: 1.25rem;
  color: #475569;
  margin: 0 0 2.5rem;
  line-height: 1.6;
}
.hero-actions {
  display: flex;
  gap: 1rem;
  justify-content: center;
  flex-wrap: wrap;
}
.btn-primary {
  padding: 0.75rem 2rem;
  background: #4f46e5;
  color: white;
  border-radius: 8px;
  text-decoration: none;
  font-weight: 500;
  transition: background 0.2s;
}
.btn-primary:hover { background: #4338ca; }
.btn-secondary {
  padding: 0.75rem 2rem;
  background: transparent;
  border: 1px solid #d1d5db;
  color: #374151;
  border-radius: 8px;
  text-decoration: none;
  font-weight: 500;
  transition: all 0.2s;
}
.btn-secondary:hover { border-color: #4f46e5; color: #4f46e5; }

/* Architecture SVG */
.arch-visual {
  max-width: 700px;
  margin: 3rem auto 0;
  padding: 2rem;
}

/* Features */
.features-section {
  padding: 4rem 2rem;
  max-width: 1000px;
  margin: 0 auto;
}
.features-title {
  text-align: center;
  font-size: 1.75rem;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 3rem;
}
.features-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}
.features-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1.5rem;
  max-width: 66%;
  margin: 0 auto;
}
.feature-card {
  padding: 1.75rem;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.feature-card:hover {
  border-color: #c7d2fe;
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.08);
}
.feature-icon { font-size: 1.5rem; margin-bottom: 0.75rem; }
.feature-card h3 {
  margin: 0 0 0.5rem;
  font-size: 1rem;
  color: #0f172a;
}
.feature-card p {
  margin: 0;
  font-size: 0.875rem;
  color: #64748b;
  line-height: 1.6;
}

/* Product showcase */
.showcase-section {
  padding: 4rem 2rem;
  background: #f8fafc;
}
.showcase-inner {
  max-width: 900px;
  margin: 0 auto;
  text-align: center;
}
.showcase-tabs {
  display: flex;
  gap: 0.5rem;
  justify-content: center;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
}
.tab-btn {
  padding: 0.5rem 1.25rem;
  border-radius: 6px;
  font-size: 0.875rem;
  cursor: pointer;
  border: none;
  background: #e2e8f0;
  color: #475569;
  font-weight: 500;
  transition: all 0.2s;
}
.tab-btn.active {
  background: #4f46e5;
  color: white;
}
.showcase-frame {
  background: #1e293b;
  border-radius: 12px;
  padding: 2rem;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
}
.showcase-placeholder {
  background: #334155;
  border-radius: 8px;
  height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 0.9rem;
}
.demo-link {
  display: inline-block;
  margin-top: 1.5rem;
  color: #4f46e5;
  text-decoration: none;
  font-weight: 500;
  font-size: 0.9rem;
}
.demo-link:hover { text-decoration: underline; }

/* Quickstart */
.quickstart-section {
  padding: 4rem 2rem;
  max-width: 700px;
  margin: 0 auto;
  text-align: center;
}
.quickstart-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 0.5rem;
}
.quickstart-desc {
  color: #64748b;
  margin: 0 0 2rem;
  font-size: 0.95rem;
}
.code-block {
  background: #1e293b;
  border-radius: 12px;
  padding: 1.5rem 2rem;
  text-align: left;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 0.8rem;
  line-height: 2;
  overflow-x: auto;
}
.code-block .comment { color: #6b7280; }
.code-block .cmd { color: #e2e8f0; }
.code-hint {
  color: #64748b;
  font-size: 0.85rem;
  margin-top: 1.5rem;
}
.code-hint span { color: #4f46e5; font-weight: 500; }

/* Tech stack */
.tech-section {
  padding: 3rem 2rem;
  background: #f8fafc;
  text-align: center;
}
.tech-label {
  font-size: 0.8rem;
  color: #6b7280;
  letter-spacing: 0.05em;
  margin: 0 0 1.5rem;
}
.tech-list {
  display: flex;
  gap: 2.5rem;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap;
}
.tech-list span {
  color: #475569;
  font-size: 0.9rem;
  font-weight: 500;
}

/* Final CTA */
.cta-section {
  padding: 4rem 2rem;
  text-align: center;
}
.cta-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 0.75rem;
}
.cta-desc {
  color: #64748b;
  margin: 0 0 2rem;
}
.cta-actions {
  display: flex;
  gap: 1rem;
  justify-content: center;
  flex-wrap: wrap;
}

/* Footer */
.landing-footer {
  background: #0f172a;
  padding: 3rem 2rem;
  color: #94a3b8;
}
.footer-grid {
  max-width: 1000px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  gap: 2rem;
}
.footer-grid h4 {
  color: #e2e8f0;
  font-size: 0.9rem;
  margin: 0 0 0.75rem;
}
.footer-grid p {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.8;
}
.footer-grid a {
  color: #94a3b8;
  text-decoration: none;
  font-size: 0.85rem;
  display: block;
  line-height: 2;
}
.footer-grid a:hover { color: #e2e8f0; }
.footer-bottom {
  max-width: 1000px;
  margin: 2rem auto 0;
  padding-top: 1.5rem;
  border-top: 1px solid #1e293b;
  font-size: 0.8rem;
  text-align: center;
}

/* Responsive */
@media (max-width: 768px) {
  .hero-title { font-size: 2rem; }
  .features-grid { grid-template-columns: 1fr; }
  .features-grid-2 { grid-template-columns: 1fr; max-width: 100%; }
  .footer-grid { grid-template-columns: 1fr 1fr; }
}
</style>

<div class="landing">

<!-- Hero -->
<section class="hero-section">
  <p class="hero-badge">Open Source Data Portal</p>
  <h1 class="hero-title">一站式智能数据平台</h1>
  <p class="hero-subtitle">数据资产、任务调度、数据治理、数据血缘、智能化。</p>
  <div class="hero-actions">
    <a href="/guide/quick-start" class="btn-primary">快速开始</a>
    <a href="https://github.com/opendata-lab/opendataworks" target="_blank" class="btn-secondary">GitHub ⭐</a>
  </div>

  <!-- Architecture SVG -->
  <div class="arch-visual">
    <svg viewBox="0 0 600 280" fill="none" xmlns="http://www.w3.org/2000/svg" style="width:100%;">
      <!-- Top modules -->
      <rect x="20" y="20" width="110" height="48" rx="10" fill="#f1f5f9" stroke="#e2e8f0" stroke-width="1.5"/>
      <text x="75" y="48" text-anchor="middle" fill="#475569" font-size="13">数据资产</text>
      <rect x="150" y="20" width="110" height="48" rx="10" fill="#f1f5f9" stroke="#e2e8f0" stroke-width="1.5"/>
      <text x="205" y="48" text-anchor="middle" fill="#475569" font-size="13">任务调度</text>
      <rect x="280" y="20" width="110" height="48" rx="10" fill="#f1f5f9" stroke="#e2e8f0" stroke-width="1.5"/>
      <text x="335" y="48" text-anchor="middle" fill="#475569" font-size="13">数据血缘</text>
      <rect x="410" y="20" width="110" height="48" rx="10" fill="#f1f5f9" stroke="#e2e8f0" stroke-width="1.5"/>
      <text x="465" y="48" text-anchor="middle" fill="#475569" font-size="13">数据治理</text>
      <rect x="540" y="20" width="50" height="48" rx="10" fill="#ede9fe" stroke="#c4b5fd" stroke-width="1.5"/>
      <text x="565" y="48" text-anchor="middle" fill="#6d28d9" font-size="13">AI</text>
      <!-- Platform layer -->
      <rect x="80" y="110" width="440" height="55" rx="12" fill="rgba(79,70,229,0.08)" stroke="#4f46e5" stroke-width="1.5"/>
      <text x="300" y="143" text-anchor="middle" fill="#4f46e5" font-size="15" font-weight="600">OpenDataWorks Platform</text>
      <!-- Bottom infra -->
      <rect x="100" y="210" width="100" height="42" rx="8" fill="#f8fafc" stroke="#e2e8f0" stroke-width="1"/>
      <text x="150" y="236" text-anchor="middle" fill="#64748b" font-size="12">MySQL</text>
      <rect x="230" y="210" width="140" height="42" rx="8" fill="#f8fafc" stroke="#e2e8f0" stroke-width="1"/>
      <text x="300" y="236" text-anchor="middle" fill="#64748b" font-size="12">DolphinScheduler</text>
      <rect x="400" y="210" width="100" height="42" rx="8" fill="#f8fafc" stroke="#e2e8f0" stroke-width="1"/>
      <text x="450" y="236" text-anchor="middle" fill="#64748b" font-size="12">Redis</text>
      <!-- Connections top to platform -->
      <line x1="75" y1="68" x2="150" y2="110" stroke="#cbd5e1" stroke-width="1.2"/>
      <line x1="205" y1="68" x2="230" y2="110" stroke="#cbd5e1" stroke-width="1.2"/>
      <line x1="335" y1="68" x2="330" y2="110" stroke="#cbd5e1" stroke-width="1.2"/>
      <line x1="465" y1="68" x2="420" y2="110" stroke="#cbd5e1" stroke-width="1.2"/>
      <line x1="565" y1="68" x2="500" y2="110" stroke="#c4b5fd" stroke-width="1.2"/>
      <!-- Connections platform to infra -->
      <line x1="200" y1="165" x2="150" y2="210" stroke="#cbd5e1" stroke-width="1.2"/>
      <line x1="300" y1="165" x2="300" y2="210" stroke="#cbd5e1" stroke-width="1.2"/>
      <line x1="400" y1="165" x2="450" y2="210" stroke="#cbd5e1" stroke-width="1.2"/>
    </svg>
  </div>
</section>

<!-- Features -->
<section class="features-section">
  <h2 class="features-title">核心能力</h2>
  <div class="features-grid">
    <div class="feature-card">
      <div class="feature-icon">📊</div>
      <h3>数据资产</h3>
      <p>ODS 到 ADS 全层级管理，字段级元信息维护，数据域分类归档。</p>
    </div>
    <div class="feature-card">
      <div class="feature-icon">⚡</div>
      <h3>任务调度</h3>
      <p>集成 DolphinScheduler，SQL/Shell 多任务类型，DAG 依赖自动编排。</p>
    </div>
    <div class="feature-card">
      <div class="feature-icon">🛡️</div>
      <h3>数据治理</h3>
      <p>生命周期管理，质量监控，执行状态实时追踪与告警。</p>
    </div>
  </div>
  <div class="features-grid-2">
    <div class="feature-card">
      <div class="feature-icon">🔗</div>
      <h3>数据血缘</h3>
      <p>自动生成血缘关系，力导向图可视化，上下游链路追踪。</p>
    </div>
    <div class="feature-card">
      <div class="feature-icon">🤖</div>
      <h3>智能化</h3>
      <p>自然语言查询数据，AI Agent 自动生成 SQL，多轮对话持续追问。</p>
    </div>
  </div>
</section>

<!-- Product Showcase -->
<section class="showcase-section">
  <div class="showcase-inner">
    <div class="showcase-tabs">
      <button v-for="tab in tabs" :key="tab.id" class="tab-btn" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}</button>
    </div>
    <div class="showcase-frame">
      <div class="showcase-placeholder">
        产品截图即将上线 — 点击下方链接体验真实产品
      </div>
    </div>
    <a href="https://opendataworks-demo.vercel.app/" target="_blank" class="demo-link">查看在线 Demo →</a>
  </div>
</section>

<!-- Quickstart -->
<section class="quickstart-section">
  <h2 class="quickstart-title">3 步启动完整环境</h2>
  <p class="quickstart-desc">无需复杂配置，Docker Compose 一键拉起所有服务。</p>
  <div class="code-block">
    <p class="comment"># 克隆并进入项目</p>
    <p class="cmd">git clone https://github.com/opendata-lab/opendataworks.git && cd opendataworks</p>
    <p class="comment"># 一键启动</p>
    <p class="cmd">cp deploy/.env.example deploy/.env</p>
    <p class="cmd">docker compose -f deploy/docker-compose.dev.yml up -d</p>
  </div>
  <p class="code-hint">启动后访问 <span>localhost:8081</span> 即可使用</p>
</section>

<!-- Tech Stack -->
<section class="tech-section">
  <p class="tech-label">BUILT WITH</p>
  <div class="tech-list">
    <span>Vue 3</span>
    <span>Spring Boot</span>
    <span>FastAPI</span>
    <span>DolphinScheduler</span>
    <span>MySQL</span>
    <span>Redis</span>
    <span>Docker</span>
  </div>
</section>

<!-- Final CTA -->
<section class="cta-section">
  <h2 class="cta-title">开始使用 OpenDataWorks</h2>
  <p class="cta-desc">开源免费，Docker 一键部署，5 分钟上手。</p>
  <div class="cta-actions">
    <a href="/guide/quick-start" class="btn-primary">快速开始</a>
    <a href="/guide/features" class="btn-secondary">查看文档</a>
    <a href="https://github.com/opendata-lab/opendataworks" target="_blank" class="btn-secondary">GitHub</a>
  </div>
</section>

<!-- Footer -->
<footer class="landing-footer">
  <div class="footer-grid">
    <div>
      <h4>OpenDataWorks</h4>
      <p>一站式智能数据平台<br>GPL-3.0 开源</p>
    </div>
    <div>
      <h4>产品</h4>
      <a href="/guide/quick-start">文档</a>
      <a href="https://opendataworks-demo.vercel.app/" target="_blank">Demo</a>
      <a href="/changelog">更新日志</a>
    </div>
    <div>
      <h4>社区</h4>
      <a href="https://github.com/opendata-lab/opendataworks" target="_blank">GitHub</a>
      <a href="https://github.com/opendata-lab/opendataworks/issues" target="_blank">Issues</a>
      <a href="https://github.com/opendata-lab/opendataworks/discussions" target="_blank">Discussions</a>
    </div>
    <div>
      <h4>资源</h4>
      <a href="/guide/quick-start">快速开始</a>
      <a href="/guide/deployment">部署指南</a>
      <a href="/guide/contribution">贡献代码</a>
    </div>
  </div>
  <div class="footer-bottom">
    <p>Copyright © 2024-present OpenDataWorks</p>
  </div>
</footer>

</div>
