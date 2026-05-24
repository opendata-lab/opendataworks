import { defineConfig } from 'vitepress'

const docsSidebar = [
  {
    text: 'Getting Started',
    collapsed: false,
    items: [
      {
        text: '快速开始',
        collapsed: false,
        items: [
          { text: '简介', link: '/guide/introduction' },
          { text: '快速上手', link: '/guide/quick-start' },
        ],
      },
      { text: '安装部署', link: '/guide/deployment' },
      { text: '配置说明', link: '/guide/configuration' },
    ],
  },
  {
    text: '功能与架构',
    collapsed: false,
    items: [
      {
        text: '系统架构',
        collapsed: true,
        items: [
          { text: '总体架构', link: '/architecture/overview' },
          { text: '后端架构', link: '/architecture/backend' },
          { text: '前端架构', link: '/architecture/frontend' },
          { text: '智能查询架构', link: '/architecture/dataagent' },
        ],
      },
      { text: '产品概念', link: '/guide/features' },
    ],
  },
  {
    text: '使用指南',
    collapsed: false,
    items: [
      { text: '数据资产', link: '/guide/metadata' },
      { text: '任务调度', link: '/guide/workflow' },
      { text: '数据血缘', link: '/guide/lineage' },
      { text: '智能查询', link: '/guide/intelligent-query' },
    ],
  },
  {
    text: 'API 参考',
    collapsed: true,
    items: [
      { text: '概览', link: '/api/overview' },
      { text: '认证', link: '/api/authentication' },
      { text: '元数据 API', link: '/api/metadata' },
      { text: '工作流 API', link: '/api/workflow' },
      { text: '智能查询 API', link: '/api/intelligent-query' },
    ],
  },
  {
    text: '参与贡献',
    collapsed: true,
    items: [
      { text: '贡献指南', link: '/guide/contribution' },
      { text: '常见问题', link: '/guide/faq' },
    ],
  },
]

export default defineConfig({
  lang: 'zh-CN',
  title: 'OpenDataWorks',
  description: '统一数据门户 — 元数据管理、工作流编排、血缘分析与智能查询',
  ignoreDeadLinks: [/localhost/],
  srcDir: '.',
  srcExclude: ['**/node_modules/**', '**/README.md'],

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
    ['link', { rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    ['link', { rel: 'stylesheet', href: 'https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500&family=Inter:wght@400;500;600;700&display=swap' }],
  ],

  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'OpenDataWorks',

    nav: [
      { text: '文档', link: '/guide/quick-start', activeMatch: '/(guide|architecture|api)/' },
      { text: '在线 Demo', link: 'https://opendataworks-demo.vercel.app/' },
      {
        text: '相关链接',
        items: [
          { text: 'GitHub', link: 'https://github.com/opendata-lab/opendataworks' },
          { text: '更新日志', link: '/changelog' },
        ],
      },
    ],

    sidebar: {
      '/guide/': docsSidebar,
      '/architecture/': docsSidebar,
      '/api/': docsSidebar,
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/opendata-lab/opendataworks' },
    ],

    search: {
      provider: 'local',
      options: {
        translations: {
          button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
          modal: {
            noResultsText: '无法找到相关结果',
            resetButtonTitle: '清除查询条件',
            footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' },
          },
        },
      },
    },

    outline: {
      label: '页面导航',
      level: [2, 3],
    },

    docFooter: {
      prev: '上一页',
      next: '下一页',
    },

    lastUpdated: {
      text: '最后更新于',
    },

    editLink: {
      pattern: 'https://github.com/opendata-lab/opendataworks/edit/main/website/:path',
      text: '在 GitHub 上编辑此页',
    },

    footer: {
      message: '基于 GPL-3.0 许可发布',
      copyright: 'Copyright © 2024-present OpenDataWorks',
    },
  },
})
