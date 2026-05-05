import axios from 'axios'

const DEFAULT_TIMEOUT = 120000
const RUNTIME_PREFIX = '/api/v1/nl2sql'
const ADMIN_PREFIX = '/api/v1/nl2sql-admin'

function getDefaultBaseUrl() {
  if (typeof window === 'undefined') {
    return 'http://localhost:8900'
  }
  return ''
}

function normalizeBaseUrl(baseURL) {
  if (baseURL === undefined || baseURL === null) {
    return getDefaultBaseUrl()
  }
  return String(baseURL).replace(/\/+$/, '')
}

function buildUrl(baseURL, path) {
  return `${normalizeBaseUrl(baseURL)}${path}`
}

function unwrapResponse(response) {
  return response?.data
}

async function extractHttpError(response) {
  try {
    const data = await response.clone().json()
    if (data?.detail) return String(data.detail)
  } catch (_error) {
    // ignore
  }

  try {
    const text = await response.text()
    if (text) return text
  } catch (_error) {
    // ignore
  }

  return `${response.status} ${response.statusText || 'Request failed'}`
}

function parseSseChunk(buffer, onEvent) {
  let rest = buffer

  while (true) {
    const splitAt = rest.indexOf('\n\n')
    if (splitAt < 0) break

    const rawEvent = rest.slice(0, splitAt)
    rest = rest.slice(splitAt + 2)

    let eventName = ''
    const dataLines = []
    const lines = rawEvent.split('\n').map((line) => line.trimEnd())

    for (const line of lines) {
      if (!line || line.startsWith(':')) continue
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
        continue
      }
      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    }

    if (!dataLines.length) continue

    try {
      const payload = JSON.parse(dataLines.join('\n'))
      if (eventName && !payload.type) payload.type = eventName
      if (eventName) payload.sse_event = eventName
      onEvent?.(payload)
    } catch (_error) {
      // ignore malformed chunks
    }
  }

  return rest
}

function createAxiosClient(baseURL, prefix, timeout, defaultHeaders = {}) {
  const request = axios.create({
    baseURL: buildUrl(baseURL, prefix),
    timeout,
    headers: { ...defaultHeaders }
  })

  request.interceptors.response.use(
    (response) => unwrapResponse(response),
    (error) => {
      const responseMessage = error?.response?.data?.detail || error?.response?.data?.message
      error.message = responseMessage || error.message || '网络错误'
      return Promise.reject(error)
    }
  )

  return request
}

export function createNl2SqlApiClient(options = {}) {
  const baseURL = normalizeBaseUrl(options.baseURL)
  const timeout = options.timeout || DEFAULT_TIMEOUT
  const defaultHeaders = options.defaultHeaders || options.headers || {}
  const runtimeRequest = createAxiosClient(baseURL, RUNTIME_PREFIX, timeout, defaultHeaders)
  const adminRequest = createAxiosClient(baseURL, ADMIN_PREFIX, timeout, defaultHeaders)

  const runtimeApi = {
    getConfig() {
      return runtimeRequest.get('/runtime-config')
    }
  }

  const topicApi = {
    createTopic(title = '新话题') {
      return runtimeRequest.post('/topics', { title })
    },
    listTopics() {
      return runtimeRequest.get('/topics')
    },
    getTopic(topicId) {
      return runtimeRequest.get(`/topics/${topicId}`)
    },
    updateTopic(topicId, data) {
      return runtimeRequest.put(`/topics/${topicId}`, data)
    },
    deleteTopic(topicId) {
      return runtimeRequest.delete(`/topics/${topicId}`)
    },
    getTopicMessages(topicId, params = {}) {
      return runtimeRequest.get(`/topics/${topicId}/messages`, { params })
    }
  }

  const taskApi = {
    deliverMessage(data) {
      return runtimeRequest.post('/tasks/deliver-message', data)
    },
    createTask(data) {
      return runtimeRequest.post('/tasks', data)
    },
    getTask(taskId) {
      return runtimeRequest.get(`/tasks/${taskId}`)
    },
    getTaskEvents(taskId, params = {}) {
      return runtimeRequest.get(`/tasks/${taskId}/events`, { params })
    },
    cancelTask(taskId) {
      return runtimeRequest.post(`/tasks/${taskId}/cancel`)
    },
    async streamTaskEvents(taskId, options = {}) {
      const { onEvent, signal, afterSeq = 0 } = options
      const response = await fetch(
        buildUrl(baseURL, `${RUNTIME_PREFIX}/tasks/${taskId}/events/stream?after_seq=${encodeURIComponent(afterSeq)}`),
        {
          method: 'GET',
          headers: { Accept: 'text/event-stream', ...defaultHeaders },
          signal
        }
      )

      if (!response.ok) {
        throw new Error(await extractHttpError(response))
      }
      if (!response.body) {
        throw new Error('SSE stream body is empty')
      }

      const decoder = new TextDecoder('utf-8')
      const reader = response.body.getReader()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        buffer = parseSseChunk(buffer, onEvent)
      }

      if (buffer.trim()) {
        parseSseChunk(`${buffer}\n\n`, onEvent)
      }
    }
  }

  const messageQueueApi = {
    query(data) {
      return runtimeRequest.post('/message-queue/queries', data)
    },
    create(data) {
      return runtimeRequest.post('/message-queue', data)
    },
    update(queueId, data) {
      return runtimeRequest.put(`/message-queue/${queueId}`, data)
    },
    remove(queueId) {
      return runtimeRequest.delete(`/message-queue/${queueId}`)
    },
    consume(queueId) {
      return runtimeRequest.post(`/message-queue/${queueId}/consume`)
    }
  }

  const scheduleApi = {
    query(data) {
      return runtimeRequest.post('/message-schedule/queries', data)
    },
    create(data) {
      return runtimeRequest.post('/message-schedule', data)
    },
    update(scheduleId, data) {
      return runtimeRequest.put(`/message-schedule/${scheduleId}`, data)
    },
    remove(scheduleId) {
      return runtimeRequest.delete(`/message-schedule/${scheduleId}`)
    },
    get(scheduleId) {
      return runtimeRequest.get(`/message-schedule/${scheduleId}`)
    },
    logs(scheduleId, data) {
      return runtimeRequest.post(`/message-schedule/${scheduleId}/logs`, data)
    }
  }

  const adminApi = {
    getSettings() {
      return adminRequest.get('/settings')
    },
    updateSettings(data) {
      return adminRequest.put('/settings', data)
    }
  }

  return {
    runtimeApi,
    topicApi,
    taskApi,
    messageQueueApi,
    scheduleApi,
    adminApi,
    health() {
      return runtimeRequest.get('/health')
    }
  }
}
