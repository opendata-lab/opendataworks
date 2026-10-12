export const PANEL_MODES = ['floating', 'docked-left', 'docked-right']
export const DOCK_WIDTH = 440
export const DOCK_RESERVE_MIN_VIEWPORT = 1024

export const normalizePanelMode = (value) => PANEL_MODES.includes(value) ? value : 'floating'

export function getPanelLayout(config, state, viewportWidth) {
  const mode = config.displayMode === 'inline' ? 'floating' : normalizePanelMode(state.panelMode)
  const side = mode === 'docked-left' ? 'left' : mode === 'docked-right' ? 'right' : null
  const width = side && state.isOpen ? Math.min(DOCK_WIDTH, viewportWidth) : 0
  return { mode, side, width, reservedWidth: viewportWidth >= DOCK_RESERVE_MIN_VIEWPORT ? width : 0, isOpen: Boolean(state.isOpen) }
}
