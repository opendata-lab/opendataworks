const fallbackCopyText = (text) => {
  if (typeof document === 'undefined' || !document.body) {
    throw new Error('Clipboard copy is not available')
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-9999px'
  textarea.style.left = '-9999px'
  textarea.style.opacity = '0'

  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, textarea.value.length)

  const copied = typeof document.execCommand === 'function' && document.execCommand('copy')
  document.body.removeChild(textarea)

  if (!copied) {
    throw new Error('Clipboard copy is not supported')
  }
}

export const copyText = async (value) => {
  const text = String(value ?? '')

  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return
    } catch (error) {
      fallbackCopyText(text)
      return
    }
  }

  fallbackCopyText(text)
}
