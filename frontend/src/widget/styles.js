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
  gap: 0;
  height: 56px;
  padding: 0;
  border: none;
  border-radius: 28px;
  background: var(--odw-widget-color);
  color: #fff;
  box-shadow:
    0 6px 16px rgba(15, 23, 42, 0.18),
    0 16px 40px rgba(15, 23, 42, 0.12),
    0 0 0 0 color-mix(in srgb, var(--odw-widget-color) 40%, transparent);
  cursor: pointer;
  overflow: hidden;
  transition: box-shadow 0.3s ease, border-radius 0.3s ease;
  animation: odw-launcher-entrance 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) both;
}

.odw-launcher:hover {
  box-shadow:
    0 8px 20px rgba(15, 23, 42, 0.22),
    0 20px 48px rgba(15, 23, 42, 0.16),
    0 0 0 4px color-mix(in srgb, var(--odw-widget-color) 20%, transparent);
}

.odw-launcher__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  flex-shrink: 0;
  border-radius: 50%;
  background: transparent;
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 0.5px;
  position: relative;
}

.odw-launcher__mark::after {
  content: '';
  position: absolute;
  inset: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
  animation: odw-launcher-pulse 2.8s ease-in-out infinite;
}

.odw-launcher__label {
  max-width: 0;
  opacity: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  padding-right: 0;
  transition: max-width 0.35s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.25s ease, padding-right 0.35s ease;
}

.odw-launcher:hover .odw-launcher__label {
  max-width: 120px;
  opacity: 1;
  padding-right: 18px;
}

.odw-panel {
  width: min(460px, calc(100vw - 32px));
  height: min(680px, calc(100vh - 48px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #dbe3ef;
  border-radius: 18px;
  background: #f4f5f7;
  box-shadow:
    0 12px 28px rgba(15, 23, 42, 0.14),
    0 28px 64px rgba(15, 23, 42, 0.12);
  animation: odw-panel-entrance 0.32s cubic-bezier(0.34, 1.4, 0.64, 1) both;
}

.odw-widget.is-inline .odw-panel {
  width: 100%;
  height: 100%;
  min-height: 420px;
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.odw-panel__header {
  min-height: 58px;
  padding: 0 14px 0 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-bottom: 1px solid color-mix(in srgb, var(--odw-widget-color) 12%, #e5eaf1);
  background: linear-gradient(135deg, #fff 60%, color-mix(in srgb, var(--odw-widget-color) 6%, #fff));
  color: #1f1f1f;
}

.odw-panel__heading {
  min-width: 0;
  flex: 1;
}

.odw-panel__title {
  font-size: 15px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.odw-panel__subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: #a0aabf;
}

.odw-icon-button {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: #eef1f5;
  color: #595959;
  line-height: 1;
  cursor: pointer;
}

.odw-icon-button:not(:disabled):hover {
  background: #e5eaf1;
}

.odw-icon-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.odw-history-toggle {
  color: #475569;
}

.odw-close-button {
  color: #64748b;
}

.odw-icon-svg {
  width: 16px;
  height: 16px;
  display: block;
}

.odw-panel__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.odw-chat {
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  background: #f6f8fb;
}

.odw-chat.is-inline {
  flex-direction: row;
}

.odw-chat-main {
  min-width: 0;
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.odw-history-backdrop {
  position: absolute;
  inset: 0;
  z-index: 5;
  background: rgba(15, 23, 42, 0.34);
}

.odw-chat.is-inline .odw-history-backdrop {
  display: none;
}

.odw-history {
  position: absolute;
  inset: 0 auto 0 0;
  z-index: 6;
  width: min(300px, 86%);
  min-width: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #dbe3ef;
  background: #fff;
  box-shadow: 16px 0 34px rgba(15, 23, 42, 0.16);
}

.odw-chat.is-inline .odw-history {
  position: relative;
  inset: auto;
  z-index: auto;
  width: 268px;
  flex: 0 0 268px;
  box-shadow: none;
}

.odw-history__header {
  padding: 14px 14px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-bottom: 1px solid #edf1f6;
}

.odw-history__title {
  font-size: 14px;
  font-weight: 700;
  color: #172033;
}

.odw-history__count {
  margin-top: 3px;
  font-size: 12px;
  color: #7b8798;
}

.odw-history__new {
  flex: 0 0 auto;
  height: 30px;
  border: none;
  border-radius: 7px;
  padding: 0 10px;
  background: var(--odw-widget-color);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.odw-history__new:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.odw-history__empty {
  padding: 22px 14px;
  color: #7b8798;
  font-size: 13px;
}

.odw-history__list {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.odw-history-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 30px;
  align-items: stretch;
  gap: 4px;
  margin-bottom: 4px;
  border-radius: 8px;
}

.odw-history-item.active {
  background: #e8f3f6;
  background: color-mix(in srgb, var(--odw-widget-color) 11%, #fff);
}

.odw-history-item__main {
  min-width: 0;
  border: none;
  border-radius: 8px;
  padding: 9px 8px;
  background: transparent;
  color: #243044;
  text-align: left;
  cursor: pointer;
}

.odw-history-item__main:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.odw-history-item__main:hover:not(:disabled) {
  background: #f3f6fa;
}

.odw-history-item__title,
.odw-history-item__meta,
.odw-history-item__preview {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.odw-history-item__title {
  font-size: 13px;
  font-weight: 700;
}

.odw-history-item__meta {
  margin-top: 4px;
  color: #8490a3;
  font-size: 11px;
}

.odw-history-item__preview {
  margin-top: 4px;
  color: #69758a;
  font-size: 12px;
}

.odw-history-item__delete {
  align-self: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: #8b98aa;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}

.odw-history-item__delete:hover:not(:disabled) {
  background: #fee2e2;
  color: #b91c1c;
}

.odw-history-item__delete:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.odw-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}

.odw-empty {
  margin-top: 42px;
  text-align: center;
  color: #68758a;
}

.odw-empty__title {
  font-size: 16px;
  font-weight: 700;
  color: #263246;
}

.odw-empty__text {
  margin-top: 8px;
  font-size: 13px;
}

.odw-alert {
  padding: 10px 12px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.odw-message {
  display: flex;
  margin-bottom: 10px;
}

.odw-message.is-user {
  justify-content: flex-end;
}

.odw-message.is-assistant {
  justify-content: flex-start;
}

.odw-bubble {
  max-width: 86%;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e1e7ef;
  color: #263246;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.is-user .odw-bubble {
  background: var(--odw-widget-color);
  border-color: var(--odw-widget-color);
  color: #fff;
}

.odw-composer {
  padding: 12px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  border-top: 1px solid #e1e7ef;
  background: #fff;
}

.odw-input {
  min-width: 0;
  resize: none;
  border: 1px solid #d8e1ed;
  border-radius: 8px;
  padding: 9px 10px;
  font: inherit;
  font-size: 14px;
  outline: none;
}

.odw-input:focus {
  border-color: var(--odw-widget-color);
}

.odw-send,
.odw-cancel {
  align-self: stretch;
  border: none;
  border-radius: 8px;
  padding: 0 14px;
  font-weight: 700;
  cursor: pointer;
}

.odw-send {
  background: var(--odw-widget-color);
  color: #fff;
}

.odw-send:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.odw-cancel {
  background: #f1f5f9;
  color: #475569;
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
  background: var(--surface);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.035);
}

.query-workbench.is-history-open:not(.is-floating) {
  grid-template-columns: 260px minmax(0, 1fr);
}

.query-workbench:not(.is-floating) .query-sidebar-backdrop {
  display: none;
}

.query-main-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}

.query-btn-history-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--sidebar-bg);
  color: var(--text-muted);
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s ease;
}

.query-btn-history-toggle:hover {
  background: var(--surface-soft);
  color: var(--text);
  border-color: var(--accent);
}

.query-icon-svg {
  width: 16px;
  height: 16px;
  display: block;
}

@container (max-width: 600px) {
  .query-workbench.is-history-open:not(.is-floating) {
    grid-template-columns: minmax(0, 1fr);
  }
  .query-workbench:not(.is-floating) .query-sidebar {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 8;
    width: min(300px, 86%);
    box-shadow: 16px 0 34px rgba(15, 23, 42, 0.16);
    border-right: 1px solid var(--sidebar-border);
  }
  .query-workbench:not(.is-floating) .query-sidebar-backdrop {
    display: block;
  }
}

.query-workbench.is-floating {
  grid-template-columns: minmax(0, 1fr);
  border: none;
  border-radius: 0 0 18px 18px;
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

.query-workbench.is-floating .query-sidebar {
  position: absolute;
  inset: 0 auto 0 0;
  z-index: 8;
  width: min(300px, 86%);
  box-shadow: 16px 0 34px rgba(15, 23, 42, 0.16);
}

.query-sidebar-backdrop {
  position: absolute;
  inset: 0;
  z-index: 7;
  background: rgba(15, 23, 42, 0.34);
}

.query-sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
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

.query-btn-new {
  height: 34px;
  padding: 0 16px;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
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
  gap: 6px;
}

.query-session-item {
  width: 100%;
  border: none;
  border-radius: 10px;
  padding: 10px 12px;
  background: transparent;
  text-align: left;
  color: var(--sidebar-text);
  cursor: pointer;
}

.query-session-item:hover:not(:disabled) {
  background: var(--surface-muted);
}

.query-session-item.active {
  background: #edf3ff;
  background: color-mix(in srgb, var(--accent) 10%, #fff);
}

.query-session-item:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.query-session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 700;
}

.query-session-meta {
  margin-top: 6px;
  color: var(--sidebar-text-muted);
  font-size: 12px;
}

.query-empty-sessions {
  padding: 18px 12px;
  color: var(--text-soft);
  font-size: 13px;
}

.query-main {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
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
  padding: 18px 26px 26px;
}

.query-workbench.is-floating .query-messages-inner {
  padding: 14px 16px 20px;
}

.query-main-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 14px;
  margin-bottom: 14px;
  border-bottom: 1px dashed var(--line);
}

.query-workbench.is-floating .query-main-head {
  gap: 10px;
  padding-bottom: 10px;
  margin-bottom: 10px;
}

.query-main-head h3 {
  margin: 0;
  color: var(--text);
  font-size: 28px;
  font-weight: 700;
  line-height: 1.25;
}

.query-workbench.is-floating .query-main-head h3 {
  font-size: 18px;
}

.query-main-subtitle {
  margin: 8px 0 0;
  color: var(--text-muted);
  font-size: 14px;
}

.query-workbench.is-floating .query-main-subtitle {
  display: none;
}

.query-model-badge {
  min-width: 200px;
  padding: 12px 14px;
  border: 1px solid var(--line-soft);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(15, 23, 42, 0.04);
}

.query-workbench.is-floating .query-model-badge {
  min-width: 0;
  max-width: 160px;
  padding: 9px 10px;
}

.query-model-badge span,
.query-model-badge strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-model-badge span {
  color: var(--text-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.query-model-badge strong {
  margin-top: 8px;
  color: var(--text);
  font-size: 14px;
}

.query-config-empty,
.query-empty {
  margin: 44px auto 0;
  text-align: center;
  color: var(--text-muted);
}

.query-config-empty-title,
.query-empty-title {
  color: var(--text);
  font-size: 18px;
  font-weight: 700;
}

.query-config-empty-text,
.query-empty-subtitle {
  margin-top: 8px;
  color: var(--text-muted);
  font-size: 14px;
}

.query-empty-mark {
  width: 52px;
  height: 52px;
  margin: 0 auto 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 18px;
  background: #fff;
  color: var(--accent);
  font-weight: 800;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.08);
}

.query-suggestions {
  margin-top: 22px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.query-suggestion {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 8px 14px;
  background: #fff;
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
}

.query-suggestion:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
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
  line-height: 1.7;
  white-space: pre-wrap;
  transition: transform 0.15s ease;
}

.query-user-bubble:hover {
  transform: scale(1.01);
}

.query-assistant-body {
  max-width: min(860px, 92%);
  color: var(--text);
}

.query-main-text,
.query-process-panel,
.query-error-card {
  border: 1px solid var(--line-soft);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 2px 12px rgba(15, 23, 42, 0.035);
}

.query-main-text {
  padding: 14px 16px;
  color: var(--text);
  font-size: 14px;
  line-height: 1.8;
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

.query-process-panel {
  margin-bottom: 12px;
  overflow: hidden;
}

.query-process-summary {
  width: 100%;
  min-height: 42px;
  border: none;
  padding: 0 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  color: var(--text-muted);
  cursor: pointer;
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
  transform: rotate(180deg);
}

.query-process-content {
  max-height: min(420px, 52vh);
  overflow-y: auto;
  border-top: 1px solid var(--line-soft);
  background: #fafbfc;
}

.query-process-content-inner {
  padding: 12px;
}

.query-process-placeholder {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
  color: #64748b;
  font-size: 12px;
}

.query-process-placeholder-preview {
  color: #94a3b8;
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.query-loading-dots {
  display: inline-flex;
  gap: 2px;
}

.query-loading-dots span {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #94a3b8;
  animation: query-dot 1.4s ease-in-out infinite;
}

.query-loading-dots span:nth-child(2) {
  animation-delay: 0.16s;
}

.query-loading-dots span:nth-child(3) {
  animation-delay: 0.32s;
}

@keyframes query-dot {
  0%, 80%, 100% { opacity: 0.15; }
  40% { opacity: 1; }
}

.query-process-thought {
  color: #475569;
  font-size: 13px;
  line-height: 1.75;
}

.query-step-row + .query-step-row {
  margin-top: 10px;
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

.query-composer-wrap {
  flex-shrink: 0;
  padding: 8px 22px 8px;
  border-top: 1px solid #e5eaf1;
  background: var(--surface);
}

.query-workbench.is-floating .query-composer-wrap {
  padding: 6px 14px 8px;
}

.query-composer {
  width: 100%;
  max-width: var(--content-max-width);
  box-sizing: border-box;
  margin: 0 auto;
  border: 1px solid var(--line-soft);
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 4px 30px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.query-composer:focus-within {
  box-shadow: 0 8px 40px color-mix(in srgb, var(--accent) 16%, transparent);
  border-color: color-mix(in srgb, var(--accent) 42%, #fff);
}

.query-composer-top {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 14px 0;
}

.query-workbench.is-floating .query-composer-top {
  display: none;
}

.query-composer-control {
  display: flex;
  align-items: center;
}

.query-select {
  max-width: 220px;
  min-width: 180px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-muted);
  color: var(--text);
  font: inherit;
  font-size: 12px;
  outline: none;
}

.query-composer-input-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 14px 6px;
}

.query-textarea {
  min-width: 0;
  flex: 1;
  min-height: 30px;
  max-height: 112px;
  box-sizing: border-box;
  padding: 5px 0;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 14px;
  line-height: 1.42;
}

.query-textarea::placeholder {
  color: var(--text-soft);
}

.query-composer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-shrink: 0;
}

.query-composer-action {
  min-width: 38px;
  height: 38px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  color: #fff;
  background: var(--accent);
  transition: opacity 0.18s ease, transform 0.18s ease, box-shadow 0.18s ease;
}

.query-composer-action:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  box-shadow: none;
}

.query-composer-action:not(:disabled):hover {
  transform: translateY(-1px);
}

.query-composer-action-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.query-composer-action-labeled {
  width: auto;
  gap: 8px;
  padding: 0 16px;
  background: #ef4444;
}

.query-composer-action-text {
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
}

.el-scrollbar {
  overflow: hidden;
  position: relative;
}

.el-scrollbar__wrap {
  overflow: auto;
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
  font-weight: 700;
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
  0% {
    opacity: 0;
    transform: translateY(20px) scale(0.8);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@keyframes odw-launcher-pulse {
  0%, 100% {
    transform: scale(1);
    opacity: 0.15;
  }
  50% {
    transform: scale(1.12);
    opacity: 0.25;
  }
}

@keyframes odw-panel-entrance {
  0% {
    opacity: 0;
    transform: translateY(16px) scale(0.96);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@keyframes odw-msg-entrance {
  0% {
    opacity: 0;
    transform: translateY(8px);
  }
  100% {
    opacity: 1;
    transform: translateY(0);
  }
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

  .odw-chat.is-inline .odw-history-backdrop {
    display: none;
  }

  .odw-chat.is-inline.is-history-expanded .odw-history-backdrop {
    display: block;
  }

  .odw-chat.is-inline .odw-history {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 6;
    width: min(300px, 86%);
    transform: translateX(-105%);
    transition: transform 0.18s ease;
    box-shadow: 16px 0 34px rgba(15, 23, 42, 0.16);
    pointer-events: none;
  }

  .odw-chat.is-inline.is-history-expanded .odw-history {
    transform: translateX(0);
    pointer-events: auto;
  }
}
`
