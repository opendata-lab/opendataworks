import request from '@/utils/request'

export const authApi = {
    // 用户名密码登录
    login(data) {
        return request({
            url: '/auth/login',
            method: 'post',
            data
        })
    },

    // 退出登录
    logout() {
        return request({
            url: '/auth/logout',
            method: 'post',
            skipErrorMessage: true
        })
    },

    // 当前登录用户（未登录时 401，由调用方静默处理）
    me() {
        return request({
            url: '/auth/me',
            method: 'get',
            skipErrorMessage: true,
            skipAuthRedirect: true
        })
    },

    // 登录后修改密码
    changePassword(data) {
        return request({
            url: '/auth/password',
            method: 'post',
            data
        })
    }
}
