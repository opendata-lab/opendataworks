const SCRIPT_URL = import.meta.env.VITE_DATAAGENT_WIDGET_JS_URL || '/dataagent/widget/opendataworks-widget.bundle.js'
const SCRIPT_ATTR = 'data-odw-dataagent-widget-script'

export function loadDataAgentWidgetScript() {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return Promise.reject(new Error('DataAgent widget script can only be loaded in a browser'))
  }
  if (window.OpenDataWorksWidget?.installWidget) {
    return Promise.resolve(window.OpenDataWorksWidget)
  }
  if (window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__) {
    return window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
  }

  const existingScript = document.querySelector(`script[${SCRIPT_ATTR}]`)
  window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__ = new Promise((resolve, reject) => {
    const script = existingScript || document.createElement('script')
    const handleLoad = () => {
      if (window.OpenDataWorksWidget?.installWidget) {
        resolve(window.OpenDataWorksWidget)
      } else {
        reject(new Error('DataAgent widget global API is not available after script load'))
      }
    }
    const handleError = () => reject(new Error(`Failed to load DataAgent widget script: ${SCRIPT_URL}`))

    script.addEventListener('load', handleLoad, { once: true })
    script.addEventListener('error', handleError, { once: true })

    if (!existingScript) {
      script.setAttribute(SCRIPT_ATTR, '')
      script.src = SCRIPT_URL
      script.async = true
      document.head.appendChild(script)
    }
  })

  return window.__ODW_DATAAGENT_WIDGET_SCRIPT_PROMISE__
}
