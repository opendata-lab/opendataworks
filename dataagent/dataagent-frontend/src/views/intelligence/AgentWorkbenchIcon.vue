<template>
  <svg
    class="icon"
    viewBox="0 0 24 24"
    aria-hidden="true"
    v-html="icons[name] || icons.file"
  ></svg>
</template>
<script setup>
defineProps({ name: { type: String, default: 'file' } })
// Fixed, local SVG paths from the approved workbench prototype.
const icons = {
  'arrow-left': '<path d="m12 5-7 7 7 7M5 12h14"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  spark:
    '<path d="m12 3 2.6 6.4L21 12l-6.4 2.6L12 21l-2.6-6.4L3 12l6.4-2.6ZM20 2v4m-2-2h4"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3"/>',
  file: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Zm0 0v6h6M8 13h8m-8 4h6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  save: '<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12l4 4v12a2 2 0 0 1-2 2Z"/><path d="M7 3v6h10V3M7 21v-8h10v8"/>',
  publish:
    '<path d="m12 3 4 7-4 6-4-6ZM8 10H5l-2 5h6m7-5h3l2 5h-6m-5 3v3m4-3v3"/>',
  send: '<path d="m21 3-7 18-4-7-7-4 18-7ZM10 14 21 3"/>',
  refresh:
    '<path d="M20 7v5h-5M4 17v-5h5m-5 0a8 8 0 0 1 13.7-5.7L20 9M4 15l2.3 2.7A8 8 0 0 0 20 12"/>',
  chat: '<path d="M21 15a3 3 0 0 1-3 3H8l-5 3V6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3ZM7 8h10M7 12h7"/>',
  code: '<path d="m8 6-6 6 6 6m8-12 6 6-6 6m-3-15-2 18"/>',
  layers: '<path d="m12 3 10 5-10 5L2 8Zm-10 9 10 5 10-5m-20 5 10 5 10-5"/>',
  plug: '<path d="M8 3v5m8-5v5M6 8h12v5a6 6 0 0 1-12 0ZM12 19v3"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v.1"/>',
  x: '<path d="m6 6 12 12M6 18 18 6"/>',
  database:
    '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  settings:
    '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3"/><circle cx="15" cy="17" r="3"/>',
  error: '<path d="m12 3 10 18H2Zm0 6v5m0 3v.1"/>',
}
</script>
