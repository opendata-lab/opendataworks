const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'])
const HTML_EXTENSIONS = new Set(['html', 'htm'])

/**
 * How the SDK can show a file in place, or '' when it can only be downloaded.
 *
 * Extension rather than media type: the attachment list a transport returns is
 * a list of workspace paths, and a run that produced `report.html` should offer
 * a preview without the host having to describe every file it wrote.
 *
 * SVG counts as an image because it is rendered through `<img>`, which does not
 * execute script in it. The same bytes in an `<iframe>` would.
 */
export function previewKindFor(name) {
  const extension = String(name || '').split('.').pop()?.toLowerCase() || ''
  if (IMAGE_EXTENSIONS.has(extension)) return 'image'
  if (HTML_EXTENSIONS.has(extension)) return 'html'
  return ''
}
