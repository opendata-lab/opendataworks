export const WIDGET_STYLES = `
:host([data-odw-widget-mode="inline"]) {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 0;
  container-type: inline-size;
}

[data-odw-widget-mount] {
  height: 100%;
}

/* Slim scrollbars for every scroll surface inside the widget (shadow-DOM scoped). */
* {
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 23, 42, 0.18) transparent;
}

*::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

*::-webkit-scrollbar-track {
  background: transparent;
}

*::-webkit-scrollbar-thumb {
  background: rgba(15, 23, 42, 0.18);
  border-radius: 999px;
}

*::-webkit-scrollbar-thumb:hover {
  background: rgba(15, 23, 42, 0.32);
}

*::-webkit-scrollbar-corner {
  background: transparent;
}

.odw-widget {
  position: fixed;
  z-index: 2147483000;
  font-family: Inter, "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
  color: #172033;
}

.odw-widget.is-bottom-right {
  right: 24px;
  bottom: 24px;
}

.odw-widget.is-bottom-left {
  left: 24px;
  bottom: 24px;
}

.odw-widget.is-inline {
  position: relative;
  z-index: auto;
  inset: auto;
  width: 100%;
  height: 100%;
  min-height: 0;
}

.odw-launcher {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--odw-widget-color);
  color: #fff;
  box-shadow:
    0 2px 8px rgba(0, 0, 0, 0.14),
    0 8px 28px rgba(0, 0, 0, 0.12);
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.2s ease;
  animation: odw-launcher-entrance 0.35s cubic-bezier(0.34, 1.2, 0.64, 1) both;
}

.odw-launcher:hover {
  transform: translateY(-2px);
  box-shadow:
    0 4px 14px rgba(0, 0, 0, 0.18),
    0 14px 40px rgba(0, 0, 0, 0.14);
}

.odw-launcher:active {
  transform: scale(0.93);
  transition-duration: 0.06s;
}

.odw-launcher__icon {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
}

.odw-panel {
  position: relative;
  width: clamp(440px, 30vw, 720px);
  height: min(860px, calc(100vh - 48px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 14px;
  background: #ffffff;
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.06),
    0 8px 24px rgba(0, 0, 0, 0.08),
    0 24px 64px rgba(0, 0, 0, 0.12);
  animation: odw-panel-entrance 0.24s cubic-bezier(0.22, 1, 0.36, 1) both;
  transition: width 0.3s cubic-bezier(0.2, 0, 0, 1);
}

.odw-widget.is-dragged {
  right: auto;
  bottom: auto;
}

.odw-widget.is-interacting .odw-panel {
  transition: none;
  user-select: none;
}

.odw-resize-handle {
  position: absolute;
  right: 2px;
  bottom: 2px;
  z-index: 12;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  padding: 2px;
  color: rgba(23, 32, 51, 0.28);
  cursor: nwse-resize;
  touch-action: none;
}

.odw-resize-handle:hover {
  color: rgba(23, 32, 51, 0.5);
}

.odw-resize-handle svg {
  width: 12px;
  height: 12px;
  display: block;
}

.odw-widget.is-inline .odw-panel {
  width: 100%;
  height: 100%;
  min-height: 420px;
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  animation: none;
}

/* ensure WidgetChat fills remaining panel height */
.odw-panel > :not(.odw-panel__header) {
  flex: 1;
  min-height: 0;
}

.odw-panel__header {
  min-height: 52px;
  padding: 0 8px 0 16px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  background: var(--odw-widget-color);
  color: #ffffff;
  border-radius: 14px 14px 0 0;
  cursor: move;
  user-select: none;
  touch-action: none;
}

.odw-panel__header .odw-icon-button {
  cursor: pointer;
  touch-action: auto;
}

.odw-widget.is-inline .odw-panel__header {
  display: none;
}

.odw-panel__heading {
  min-width: 0;
  flex: 1;
}

.odw-panel__title {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.1px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.odw-panel__subtitle {
  margin-top: 1px;
  font-size: 11px;
  letter-spacing: 0.1px;
  color: rgba(255, 255, 255, 0.58);
}

.odw-panel__actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.odw-icon-button {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: rgba(255, 255, 255, 0.72);
  line-height: 1;
  cursor: pointer;
  transition: background 0.14s ease, color 0.14s ease;
}

.odw-icon-button:not(:disabled):hover {
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
}

.odw-icon-button:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.odw-icon-svg {
  width: 16px;
  height: 16px;
  display: block;
}

.query-workbench {
  --sidebar-bg: #ffffff;
  --sidebar-border: #e5eaf1;
  --sidebar-text: #1f1f1f;
  --sidebar-text-muted: #8c8c8c;
  --surface: #f4f5f7;
  --surface-muted: #f9fafc;
  --surface-soft: #eef1f5;
  --line: #e5eaf1;
  --line-soft: #eff1f5;
  --text: #1f1f1f;
  --text-muted: #595959;
  --text-soft: #a0aabf;
  --accent: var(--odw-widget-color);
  --accent-soft: color-mix(in srgb, var(--odw-widget-color) 10%, #fff);
  --content-max-width: clamp(680px, 82%, 1180px);
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  border: 1px solid #e5eaf1;
  border-radius: 18px;
  overflow: hidden;
  position: relative;
  background: #fff;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.035);
}

.query-workbench.is-history-open {
  grid-template-columns: 220px minmax(0, 1fr);
}

/* Floating mode: history panel is an overlay drawer so opening/closing it
   never changes the panel width or squeezes the chat area. */
.query-workbench.is-floating.is-history-open {
  grid-template-columns: minmax(0, 1fr);
}

.query-workbench.is-floating .query-sidebar {
  position: absolute;
  inset: 0 auto 0 0;
  z-index: 12;
  width: min(240px, 80%);
  box-shadow: 16px 0 34px rgba(15, 23, 42, 0.16);
  border-right: 1px solid var(--sidebar-border);
}

.query-workbench.is-floating .query-sidebar-backdrop {
  display: block;
}

.query-workbench .query-sidebar-backdrop {
  display: none;
}

.query-main-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}

@container (max-width: 600px) {
  .query-workbench.is-history-open {
    grid-template-columns: minmax(0, 1fr);
  }
  .query-workbench .query-sidebar {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 12;
    width: min(240px, 80%);
    box-shadow: 16px 0 34px rgba(15, 23, 42, 0.16);
    border-right: 1px solid var(--sidebar-border);
  }
  .query-workbench .query-sidebar-backdrop {
    display: block;
  }
}

.query-workbench.is-floating {
  border: none;
  border-radius: 0 0 14px 14px;
  box-shadow: none;
}

.query-sidebar {
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-border);
  color: var(--sidebar-text);
}

.query-sidebar-backdrop {
  position: absolute;
  inset: 0;
  z-index: 11;
  background: rgba(15, 23, 42, 0.34);
}

.query-sidebar-head {
  display: flex;
  align-items: center;
  padding: 4px 8px 16px;
}

.query-brand {
  font-size: 17px;
  font-weight: 700;
  color: var(--sidebar-text);
}

.query-brand-meta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-soft);
}

.query-sidebar-brand {
  font-size: 14px;
  font-weight: 600;
  color: var(--sidebar-text);
}

.query-btn-new {
  width: 100%;
  height: 32px;
  padding: 0 12px;
  border: none;
  border-radius: 6px;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: filter 0.15s ease;
}

.query-btn-new:hover:not(:disabled) {
  filter: brightness(1.1);
}

.query-btn-new:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.query-sidebar-search {
  padding: 0 8px 12px;
}

.query-search-input {
  width: 100%;
  height: 34px;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 0 12px;
  background: var(--surface-muted);
  color: var(--text);
  font: inherit;
  font-size: 13px;
  outline: none;
}

.query-search-input:focus {
  border-color: var(--accent);
  background: #fff;
}

.query-session-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.query-session-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.query-session-item {
  width: 100%;
  border: none;
  border-radius: 8px;
  padding: 8px 10px;
  background: transparent;
  text-align: left;
  color: var(--sidebar-text);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.query-session-item:hover:not(:disabled) {
  background: var(--surface-muted);
}

.query-session-item.active {
  background: color-mix(in srgb, var(--accent) 10%, #fff);
}

.query-session-item:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.query-session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 400;
}

.query-session-meta {
  flex-shrink: 0;
  color: var(--sidebar-text-muted);
  font-size: 11px;
}

.query-session-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 5px;
  vertical-align: middle;
}

.query-session-dot.is-error {
  background: #F56C6C;
}

.query-session-dot.is-suspended {
  background: #A0AABF;
}

.query-empty-sessions {
  padding: 18px 12px;
  color: var(--text-soft);
  font-size: 13px;
}

.query-session-loading {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.query-session-spinner {
  width: 14px;
  height: 14px;
  color: var(--accent);
  animation: query-spin 1s linear infinite;
}

.query-session-spinner-track {
  stroke: rgba(0, 0, 0, 0.05);
}

.query-session-spinner-head {
  stroke: currentColor;
}

@keyframes query-spin {
  to { transform: rotate(360deg); }
}

.query-main {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  position: relative;
}

.query-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.query-messages-inner {
  width: 100%;
  max-width: var(--content-max-width);
  box-sizing: border-box;
  margin: 0 auto;
  padding: 18px 26px 160px;
}

.query-workbench.is-floating .query-messages-inner {
  padding: 14px 16px 120px;
}

.query-config-empty {
  margin: 44px auto 0;
  text-align: center;
  color: var(--text-muted);
}

.query-config-empty-title {
  color: var(--text);
  font-size: 18px;
  font-weight: 700;
}

.query-config-empty-text {
  margin-top: 8px;
  color: var(--text-muted);
  font-size: 14px;
}

.query-messages-inner.is-empty {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.query-landing {
  width: 100%;
  max-width: 680px;
  padding: 32px 16px 16px;
}

.query-landing-greeting {
  font-size: 26px;
  font-weight: 700;
  color: #162131;
  margin-bottom: 18px;
  text-align: center;
}

.query-workbench.is-floating .query-landing-greeting {
  font-size: 20px;
  margin-bottom: 14px;
}

.query-landing-suggestions-title {
  text-align: center;
  color: #64748b;
  font-size: 13px;
  margin-bottom: 8px;
}

.query-suggestions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.query-workbench.is-floating .query-suggestions {
  grid-template-columns: 1fr;
}

.query-suggestion {
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  padding: 14px 16px;
  background: #fff;
  color: var(--text);
  font-size: 13px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
}

.query-workbench.is-floating .query-suggestion {
  padding: 10px 12px;
}

.query-suggestion:hover:not(:disabled) {
  border-color: var(--accent);
  box-shadow: 0 2px 10px color-mix(in srgb, var(--accent) 14%, transparent);
}

.query-suggestion:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.query-message-row {
  display: flex;
  margin-bottom: 18px;
  animation: odw-msg-entrance 0.3s ease both;
}

.query-message-user {
  justify-content: flex-end;
}

.query-message-assistant {
  justify-content: flex-start;
}

.query-user-bubble {
  max-width: min(720px, 82%);
  border-radius: 18px 18px 4px 18px;
  padding: 12px 16px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  transition: transform 0.15s ease;
}

.query-user-bubble:hover {
  transform: scale(1.01);
}

.query-assistant-body {
  width: min(860px, 92%);
  color: var(--text);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.query-error-card {
  border: 1px solid var(--line-soft);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.025);
}

.query-main-text {
  padding: 2px 0;
  color: var(--text);
  font-size: 14px;
  line-height: 1.65;
}

.query-main-text p,
.query-process-thought-content p {
  margin: 0 0 10px;
}

.query-main-text p:last-child,
.query-process-thought-content p:last-child {
  margin-bottom: 0;
}

.query-main-text pre,
.query-main-text code,
.query-process-thought-content code {
  border-radius: 8px;
  background: #f6f8fb;
}

.query-main-text table {
  width: 100%;
  border-collapse: collapse;
  margin: 10px 0;
  font-size: 13px;
}

.query-main-text table th,
.query-main-text table td {
  border: 1px solid var(--line);
  padding: 7px 10px;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
}

.query-main-text table th {
  background: #f8fbff;
  font-weight: 600;
  color: #607185;
  white-space: nowrap;
}

.query-main-text table tr:nth-child(even) td {
  background: #f9fafc;
}

.query-process-panel {
  width: 100%;
  overflow: hidden;
  border: 1px solid #dbe3ef;
  border-radius: 10px;
  background: #f9fafc;
}

.query-process-summary {
  width: 100%;
  border: none;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: transparent;
  color: #334155;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease;
}

.query-process-summary:hover {
  background: rgba(0, 0, 0, 0.03);
}

.query-process-summary-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-weight: 700;
  color: var(--text);
}

.query-process-badge-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  animation: query-process-pulse 1.4s ease-in-out infinite;
}

.query-process-summary-preview {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  font-size: 12px;
}

.query-process-chevron {
  width: 16px;
  height: 16px;
  transition: transform 0.18s ease;
}

.query-process-chevron.open {
  transform: rotate(90deg);
}

.query-process-content {
  max-height: min(420px, 52vh);
  overflow-y: auto;
  border-top: 1px solid var(--line-soft);
  background: #fafbfc;
}

.query-tool-row {
  border-radius: 10px;
  overflow: hidden;
}

.query-process-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.query-process-thought {
  padding: 12px 16px;
  font-size: 13px;
  line-height: 1.65;
  color: #595959;
}

.query-process-thought p { margin: 0 0 8px; }
.query-process-thought p:last-child { margin: 0; }

.query-typing-indicator {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 8px 2px;
}

.query-typing-indicator span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  opacity: 0.35;
  animation: query-typing 1.2s ease-in-out infinite;
}

.query-typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.query-typing-indicator span:nth-child(3) { animation-delay: 0.4s; }

@keyframes query-typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30% { transform: translateY(-5px); opacity: 1; }
}

.query-error-card {
  display: inline-flex;
  gap: 8px;
  align-items: flex-start;
  padding: 12px 14px;
  color: #b91c1c;
  background: #fff7f7;
  border-color: #fecaca;
  font-size: 13px;
}

.query-error-banner {
  margin-bottom: 16px;
}

.query-error-label {
  font-weight: 700;
}

.query-cursor {
  display: inline-block;
  margin-left: 2px;
  color: var(--accent);
  animation: query-process-pulse 1s ease-in-out infinite;
}

.query-composer-bar {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  z-index: 10;
  padding-top: 32px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, rgba(255, 255, 255, 0.85) 30%, #ffffff 50%);
  border-top: none;
}

.query-composer-bar.is-landing {
  top: 0;
  padding-top: 0;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.query-composer-wrap {
  width: 100%;
  box-sizing: border-box;
  max-width: var(--content-max-width);
  margin: 0 auto;
  padding: 12px 26px 16px;
}

.query-workbench.is-floating .query-composer-wrap {
  padding: 10px 16px 14px;
}

.query-composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 8px 10px 8px 16px;
  border: 1px solid #dde2ea;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.query-composer:focus-within {
  border-color: #b0bbcc;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}

.query-textarea {
  flex: 1;
  min-width: 0;
  min-height: 22px;
  max-height: 160px;
  box-sizing: border-box;
  padding: 4px 0;
  margin-bottom: 0px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 14px;
  line-height: 1.5;
  display: block;
  overflow-y: auto;
}

.query-textarea::placeholder {
  color: var(--text-soft);
}

.query-composer-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.query-composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 5px;
  padding-inline: 4px;
}

.query-composer-hint {
  flex-shrink: 0;
  color: #9aa5b1;
  font-size: 11px;
  line-height: 1.4;
  white-space: nowrap;
}

.query-model-selector {
  display: flex;
  gap: 4px;
}

.query-model-select {
  height: 24px;
  padding: 0 6px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #8a96a6;
  font: inherit;
  font-size: 11px;
  outline: none;
  max-width: 120px;
  cursor: pointer;
}

.query-model-select:hover {
  background: #eef1f5;
  color: #4a5568;
}

.query-model-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.query-workbench.is-floating .query-model-selector {
  display: none;
}

.query-send-btn {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  color: #606878;
  background: #e8eaed;
  transition: background 0.18s ease, color 0.18s ease, transform 0.18s ease;
}

.query-send-btn:not(:disabled):not(.query-cancel-btn) {
  background: linear-gradient(135deg, var(--accent), color-mix(in srgb, var(--accent) 72%, #000));
  color: #fff;
}

.query-send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.query-send-btn:not(:disabled):hover {
  transform: scale(1.06);
}

.query-cancel-btn {
  background: linear-gradient(135deg, #ef4444, #b91c1c) !important;
  color: #fff !important;
}


.tool-output {
  border: 1px solid var(--line-soft);
  border-radius: 14px;
  background: #fff;
  overflow: hidden;
  color: var(--text);
  font-size: 13px;
}

.tool-output-head,
.shell-trace-summary {
  width: 100%;
  box-sizing: border-box;
  border: none;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  background: #fff;
  color: var(--text);
}

.tool-output-label,
.shell-trace-summary-text {
  font-size: 12px;
  font-weight: 500;
  color: #8c96a8;
}

.tool-output-meta,
.shell-trace-summary-status,
.tool-output-summary {
  color: var(--text-muted);
  font-size: 12px;
}

.tool-output-panel,
.shell-trace-panel {
  border-top: 1px solid var(--line-soft);
  background: #fafbfc;
}

.tool-code,
.shell-trace-output,
.shell-trace-command {
  margin: 0;
  padding: 12px;
  overflow: auto;
  background: #f6f8fb;
  color: #334155;
  font-size: 12px;
  line-height: 1.65;
}

.tool-table-wrap {
  max-width: 100%;
  overflow: auto;
}

.tool-table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;
}

.tool-table th,
.tool-table td {
  border: 1px solid var(--line);
  padding: 8px 10px;
  text-align: left;
  font-size: 12px;
}

.tool-chart {
  min-height: 260px;
}

@keyframes query-process-pulse {
  0%, 100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}

@keyframes odw-launcher-entrance {
  from { opacity: 0; transform: scale(0.6); }
  to   { opacity: 1; transform: scale(1); }
}

@keyframes odw-panel-entrance {
  from { opacity: 0; transform: translateY(14px) scale(0.98); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}

@keyframes odw-msg-entrance {
  from { opacity: 0; transform: translateY(6px); }
  to   { opacity: 1; transform: translateY(0); }
}

@media (max-width: 520px) {
  .odw-widget.is-bottom-right,
  .odw-widget.is-bottom-left {
    right: 12px;
    bottom: 12px;
    left: 12px;
  }

  .odw-panel {
    width: calc(100vw - 24px);
    height: min(620px, calc(100vh - 24px));
  }
}

@media (max-width: 720px) {
  .odw-widget.is-inline .odw-panel {
    min-height: 360px;
  }
}

/* ── ToolOutputRenderer (inlined so Shadow DOM always has these styles) ──── */
.tool-output{padding:10px 14px;border:1px solid #eff1f5;border-radius:12px;background:#fff}.tool-output-shell{padding:2px 0;border:none;border-radius:0;background:transparent}.tool-output-chart-direct{padding:0;border:none;border-radius:0;background:transparent}.tool-output-flat{padding:0;border:none;border-radius:0;background:transparent;box-shadow:none}.tool-output.failed{border-color:#be185d26;background:#fff8fb}.tool-output-shell.failed,.tool-output-chart-direct.failed{background:transparent}.shell-trace+.tool-output-head,.shell-trace+.tool-output-summary,.shell-trace+.tool-output-error{margin-top:14px}.shell-trace-summary{width:100%;display:flex;align-items:center;gap:10px;padding:0;border:none;background:transparent;cursor:pointer;text-align:left}.shell-trace-summary-static{cursor:default}.shell-trace-summary-text{flex:1;min-width:0;color:#8c96a8;font-size:12px;font-weight:500;line-height:1.55;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.shell-trace-summary-status{font-size:12px;font-weight:600;color:#8a8a8a}.shell-trace-summary-status.is-running,.shell-trace-summary-status.is-success{color:#6b7280}.shell-trace-summary-status.is-failed{color:#9f1239}.shell-trace-chevron-icon{width:14px;height:14px;color:#a0aabf;flex-shrink:0;transition:transform .18s ease}.shell-trace-chevron-icon.open{transform:rotate(180deg)}.tool-output-panel{margin-top:10px;padding:10px 12px;border:1px solid #eff1f5;border-radius:12px;background:#fff}.tool-output-body-scroll{max-height:360px;overflow-y:auto;overscroll-behavior:contain}.shell-trace-panel{margin-top:6px;border:1px solid #E5EAF1;background:#f9fafc}.shell-trace-command,.shell-trace-output{margin:12px 0 0;padding:0;background:transparent;color:#3f3f3f;font-size:12px;line-height:1.7;overflow:visible;white-space:pre-wrap;word-break:break-word;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,Liberation Mono,monospace}.shell-trace-description{margin-top:10px;color:#7b7b7b;font-size:12px;line-height:1.6}.shell-trace-empty{margin-top:12px;color:#9a9a9a;font-size:12px;line-height:1.6}.tool-markdown{margin-top:12px;padding:14px 16px;border-radius:14px;background:#fff;border:1px solid #dbe3ec}.tool-markdown-body{color:#334155;font-size:13px;line-height:1.7;word-break:break-word}.tool-markdown-body h1,.tool-markdown-body h2,.tool-markdown-body h3,.tool-markdown-body h4,.tool-markdown-body h5,.tool-markdown-body h6{margin:0 0 10px;color:#162131;font-weight:700;line-height:1.4}.tool-markdown-body p,.tool-markdown-body ul,.tool-markdown-body ol,.tool-markdown-body blockquote{margin:0 0 10px}.tool-markdown-body ul,.tool-markdown-body ol{padding-left:18px}.tool-markdown-body code{padding:1px 5px;border-radius:6px;background:#f4f7fb;color:#1f3b57;font-size:12px;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,Liberation Mono,monospace}.tool-markdown-body pre{margin:10px 0;padding:12px 14px;border-radius:12px;background:#102033;color:#edf5ff;overflow:visible}.tool-markdown-body pre code{padding:0;background:transparent;color:inherit}.tool-markdown-toggle{margin-top:6px;padding:0;border:none;background:transparent;color:#31567a;font-size:12px;font-weight:600;cursor:pointer}.tool-markdown-toggle:hover{color:#1d3f5e}.tool-output-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.tool-output-head.is-interactive{cursor:pointer;-webkit-user-select:none;user-select:none}.tool-output-head-content{display:flex;flex-direction:column}.tool-output-head-right{display:flex;align-items:center;gap:8px}.tool-output-head-chevron{width:14px;height:14px;color:#a0aabf;flex-shrink:0;transition:transform .18s ease}.tool-output-head-chevron.open{transform:rotate(-180deg)}.tool-output-status-check{width:14px;height:14px;color:#4f81ff;flex-shrink:0}.tool-output-label{font-size:12px;font-weight:500;color:#8c96a8}.tool-output-meta{margin-top:4px;font-size:12px;color:#607185}.tool-output-chip{padding:5px 10px;border-radius:999px;background:#eef6ff;color:#31567a;font-size:12px;font-weight:600}.tool-output-summary{margin-top:12px;font-size:13px;line-height:1.65;color:#334155}.tool-output-error{margin-top:12px;padding:10px 12px;border-radius:12px;background:#be185d14;color:#9f1239;font-size:13px;line-height:1.6}.tool-code{margin:14px 0 0;padding:14px 16px;border-radius:14px;background:#102033;color:#edf5ff;font-size:12px;line-height:1.7;overflow:visible}.tool-code-light{background:#f3f7fb;color:#233142}.tool-table-wrap{margin-top:14px;border:1px solid #e1e8f0;border-radius:14px;background:#fff;overflow-x:auto;overscroll-behavior:contain}.tool-table{width:100%;border-collapse:collapse;min-width:480px}.tool-table th,.tool-table td{padding:10px 12px;border-bottom:1px solid #edf2f7;text-align:left;font-size:12px;color:#233142;white-space:pre-wrap;word-break:break-word;vertical-align:top}.tool-table th{background:#f8fbff;color:#607185;font-weight:700;white-space:nowrap}.tool-chart{display:block;margin-top:8px;box-sizing:border-box;min-height:240px;height:clamp(240px,40vh,360px);width:100%;max-width:100%;min-width:0;border-radius:14px;background:#f9fafc;border:1px solid #EEF1F5;padding:8px}.tool-output-empty{margin-top:14px;color:#8da0b3;font-size:13px}

/* ── Widget compact tool-call box (override inlined defaults above) ──────── */
.query-workbench .tool-output:not(.tool-output-shell):not(.tool-output-flat):not(.tool-output-chart-direct){padding:8px 12px;border-radius:12px}
.query-workbench .tool-output:not(.tool-output-shell):not(.tool-output-flat):not(.tool-output-chart-direct) .tool-output-head{padding:0}
.query-workbench .tool-output-panel{margin-top:8px;padding:8px 10px;border-radius:10px}
.query-workbench .tool-output-body-scroll{max-height:260px}
.query-workbench .tool-code{margin-top:10px;padding:10px 12px;border-radius:10px}
.query-workbench .tool-table-wrap{margin-top:10px}
.query-workbench .tool-chart{margin-top:6px;min-height:220px;height:220px;border-radius:10px;padding:6px}
.query-workbench.is-floating .tool-output-body-scroll{max-height:220px}
.query-workbench.is-floating .tool-chart{min-height:190px;height:190px}
`
