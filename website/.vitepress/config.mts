import { defineConfig } from 'vitepress'

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
  ],

  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'OpenDataWorks',

    nav: [
      { text: '指南', link: '/guide/quick-start', activeMatch: '/guide/' },
      { text: '架构', link: '/architecture/overview', activeMatch: '/architecture/' },
      { text: 'API', link: '/api/overview', activeMatch: '/api/' },
      {
        text: '相关链接',
        items: [
          { text: 'GitHub', link: 'https://github.com/opendata-lab/opendataworks' },
          { text: '更新日志', link: '/changelog' },
        ],
      },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '入门',
          items: [
            { text: '快速开始', link: '/guide/quick-start' },
            { text: '安装部署', link: '/guide/deployment' },
            { text: '配置说明', link: '/guide/configuration' },
          ],
        },
        {
          text: '核心功能',
          items: [
            { text: '功能概览', link: '/guide/features' },
            { text: '元数据管理', link: '/guide/metadata' },
            { text: '工作流编排', link: '/guide/workflow' },
            { text: '数据血缘', link: '/guide/lineage' },
            { text: '智能查询', link: '/guide/intelligent-query' },
          ],
        },
        {
          text: '参与贡献',
          items: [
            { text: '贡献指南', link: '/guide/contribution' },
            { text: '常见问题', link: '/guide/faq' },
          ],
        },
      ],
      '/architecture/': [
        {
          text: '架构设计',
          items: [
            { text: '总体架构', link: '/architecture/overview' },
            { text: '后端架构', link: '/architecture/backend' },
            { text: '前端架构', link: '/architecture/frontend' },
            { text: '智能查询架构', link: '/architecture/dataagent' },
          ],
        },
      ],
      '/api/': [
        {
          text: 'API 参考',
          items: [
            { text: '概览', link: '/api/overview' },
            { text: '认证', link: '/api/authentication' },
            { text: '元数据 API', link: '/api/metadata' },
            { text: '工作流 API', link: '/api/workflow' },
            { text: '智能查询 API', link: '/api/intelligent-query' },
          ],
        },
      ],
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
