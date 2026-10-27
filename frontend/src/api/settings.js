import request from '@/utils/request'

export const settingsApi = {
    // Get DolphinScheduler config
    getDolphinConfig() {
        return request({
            url: '/v1/settings/dolphin',
            method: 'get'
        })
    },

    // Update DolphinScheduler config
    updateDolphinConfig(data) {
        return request({
            url: '/v1/settings/dolphin',
            method: 'put',
            data
        })
    },

    // Test DolphinScheduler connection
    testDolphinConnection(data) {
        return request({
            url: '/v1/settings/dolphin/test',
            method: 'post',
            data
        })
    },

    listDolphinConfigs(params = {}) {
        return request({
            url: '/v1/settings/dolphin/configs',
            method: 'get',
            params
        })
    },

    getDolphinConfigById(id) {
        return request({
            url: `/v1/settings/dolphin/configs/${id}`,
            method: 'get'
        })
    },

    createDolphinConfig(data) {
        return request({
            url: '/v1/settings/dolphin/configs',
            method: 'post',
            data
        })
    },

    updateDolphinConfigById(id, data) {
        return request({
            url: `/v1/settings/dolphin/configs/${id}`,
            method: 'put',
            data
        })
    },

    deleteDolphinConfig(id) {
        return request({
            url: `/v1/settings/dolphin/configs/${id}`,
            method: 'delete'
        })
    },

    setDefaultDolphinConfig(id) {
        return request({
            url: `/v1/settings/dolphin/configs/${id}/default`,
            method: 'post'
        })
    },

    testSavedDolphinConnection(id) {
        return request({
            url: `/v1/settings/dolphin/configs/${id}/test`,
            method: 'post'
        })
    },

    listMinioConfigs(params = {}) {
        return request({
            url: '/v1/settings/minio',
            method: 'get',
            params
        })
    },

    getMinioConfig(id) {
        return request({
            url: `/v1/settings/minio/${id}`,
            method: 'get'
        })
    },

    createMinioConfig(data) {
        return request({
            url: '/v1/settings/minio',
            method: 'post',
            data
        })
    },

    updateMinioConfig(id, data) {
        return request({
            url: `/v1/settings/minio/${id}`,
            method: 'put',
            data
        })
    },

    deleteMinioConfig(id) {
        return request({
            url: `/v1/settings/minio/${id}`,
            method: 'delete'
        })
    },

    setDefaultMinioConfig(id) {
        return request({
            url: `/v1/settings/minio/${id}/default`,
            method: 'post'
        })
    },

    // 智能助手设置：门户智能问数与智能元数据各自使用的助手
    getAgentSettings() {
        return request({
            url: '/v1/settings/agent',
            method: 'get'
        })
    },

    updateAgentSettings(data) {
        return request({
            url: '/v1/settings/agent',
            method: 'put',
            data
        })
    }
}
