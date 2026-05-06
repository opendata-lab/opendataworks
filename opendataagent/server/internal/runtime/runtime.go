package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"sort"
	"strings"
	"time"

	sdkapi "github.com/stellarlinkco/agentsdk-go/pkg/api"
	sdkconfig "github.com/stellarlinkco/agentsdk-go/pkg/config"
	sdkmodel "github.com/stellarlinkco/agentsdk-go/pkg/model"
	sdkskills "github.com/stellarlinkco/agentsdk-go/pkg/runtime/skills"
	sdktool "github.com/stellarlinkco/agentsdk-go/pkg/tool"

	"opendataagent/server/internal/agent"
	"opendataagent/server/internal/models"
	skillcatalog "opendataagent/server/internal/skills"
)

type RunInput struct {
	Prompt       string
	SessionID    string
	ProjectRoot  string
	ModelFactory sdkapi.ModelFactory
	SkillEntries []skillcatalog.Entry
	MCPServers   []models.MCPServer
}

type TaskResult struct {
	Final  string
	Usage  map[string]interface{}
	Blocks []models.MessageBlock
}

type EventEmitter func(models.TaskEvent)

func Run(ctx context.Context, input RunInput, emit EventEmitter) (TaskResult, error) {
	if strings.TrimSpace(input.Prompt) == "" {
		return TaskResult{}, errors.New("prompt is empty")
	}
	if strings.TrimSpace(input.SessionID) == "" {
		input.SessionID = "oda-session"
	}
	if input.ModelFactory == nil {
		return TaskResult{}, errors.New("model factory is required")
	}

	collector := newCollector()
	emitEvent := func(event models.TaskEvent) {
		collector.Apply(event)
		if emit != nil {
			emit(event)
		}
	}

	rt, err := sdkapi.New(ctx, buildOptions(input))
	if err != nil {
		return TaskResult{}, err
	}
	defer rt.Close()

	stream, err := rt.RunStream(ctx, sdkapi.Request{
		Prompt:    input.Prompt,
		SessionID: input.SessionID,
	})
	if err != nil {
		return TaskResult{}, err
	}

	var streamErr error
	for streamEvent := range stream {
		if streamEvent.Type == sdkapi.EventError && streamErr == nil {
			streamErr = errors.New(defaultString(fmt.Sprint(streamEvent.Output), "runtime error"))
		}
		for _, taskEvent := range convertStreamEvent(streamEvent) {
			emitEvent(taskEvent)
		}
	}
	result := collector.Result()
	if streamErr != nil {
		return result, streamErr
	}
	if isEmptyTaskResult(result) {
		return result, errors.New("model returned empty response")
	}
	return result, nil
}

func isEmptyTaskResult(result TaskResult) bool {
	if strings.TrimSpace(result.Final) != "" {
		return false
	}
	for _, block := range result.Blocks {
		if strings.TrimSpace(block.Text) != "" {
			return false
		}
		if block.Tool != nil {
			return false
		}
	}
	return true
}

func buildOptions(input RunInput) sdkapi.Options {
	settings := sdkconfig.GetDefaultSettings()
	settings.Sandbox = &sdkconfig.SandboxConfig{Enabled: boolPtr(false)}
	settings.MCP = buildMCPConfig(input.MCPServers)
	toolsDisabled := envBool("ODA_DISABLE_AGENT_TOOLS")

	enabledBuiltinTools := []string{"skill", "read", "glob", "grep", "bash"}
	customTools := []sdktool.Tool{newRuntimeContextTool(input.SkillEntries, input.MCPServers)}
	skillRegistrations := buildSkillRegistrations(input.SkillEntries)
	if toolsDisabled {
		enabledBuiltinTools = nil
		customTools = nil
		skillRegistrations = nil
	}

	return sdkapi.Options{
		EntryPoint:          sdkapi.EntryPointPlatform,
		ProjectRoot:         input.ProjectRoot,
		ModelFactory:        input.ModelFactory,
		SettingsOverrides:   &settings,
		EnabledBuiltinTools: enabledBuiltinTools,
		CustomTools:         customTools,
		Skills:              skillRegistrations,
		MaxIterations:       6,
		Timeout:             5 * time.Minute,
	}
}

func NewMockModelFactory(skillEntries []skillcatalog.Entry, mcpServers []models.MCPServer) sdkapi.ModelFactory {
	return sdkapi.ModelFactoryFunc(func(context.Context) (sdkmodel.Model, error) {
		return &mockModel{skillEntries: skillEntries, mcpServers: mcpServers}, nil
	})
}

func buildSkillRegistrations(entries []skillcatalog.Entry) []sdkapi.SkillRegistration {
	registrations := make([]sdkapi.SkillRegistration, 0, len(entries))
	for _, entry := range entries {
		entry := entry
		skillName := normalizeSkillName(entry)
		if skillName == "" {
			continue
		}
		registrations = append(registrations, sdkapi.SkillRegistration{
			Definition: sdkskills.Definition{
				Name:        skillName,
				Description: defaultString(entry.Description, entry.Name),
				Metadata: map[string]string{
					"location": entry.FilePath,
					"source":   entry.Source,
					"folder":   entry.Folder,
				},
				Matchers: buildMatchers(entry),
			},
			Handler: sdkskills.HandlerFunc(func(context.Context, sdkskills.ActivationContext) (sdkskills.Result, error) {
				return sdkskills.Result{
					Skill:  skillName,
					Output: skillPrompt(entry),
					Metadata: map[string]interface{}{
						"folder": entry.Folder,
						"source": entry.Source,
					},
				}, nil
			}),
		})
	}
	return registrations
}

func buildMatchers(entry skillcatalog.Entry) []sdkskills.Matcher {
	tokens := map[string]struct{}{}
	for _, piece := range []string{entry.Folder, entry.Name, entry.Description} {
		for _, token := range strings.Fields(strings.ToLower(strings.NewReplacer("_", " ", "-", " ", "/", " ").Replace(piece))) {
			token = strings.TrimSpace(token)
			if len(token) < 2 {
				continue
			}
			tokens[token] = struct{}{}
		}
	}
	if len(tokens) == 0 {
		return nil
	}
	keywords := make([]string, 0, len(tokens))
	for token := range tokens {
		keywords = append(keywords, token)
	}
	sort.Strings(keywords)
	return []sdkskills.Matcher{sdkskills.KeywordMatcher{Any: keywords}}
}

func skillPrompt(entry skillcatalog.Entry) string {
	var builder strings.Builder
	if strings.TrimSpace(entry.RuntimeContent) != "" {
		builder.WriteString(strings.TrimSpace(entry.RuntimeContent))
	}
	if builder.Len() > 0 {
		builder.WriteString("\n\n")
	}
	builder.WriteString("Skill root directory: ")
	builder.WriteString(entry.BaseDir)
	return builder.String()
}

func buildMCPConfig(servers []models.MCPServer) *sdkconfig.MCPConfig {
	if len(servers) == 0 {
		return nil
	}
	configs := map[string]sdkconfig.MCPServerConfig{}
	for _, server := range servers {
		if !server.Enabled {
			continue
		}
		name := strings.TrimSpace(server.Name)
		if name == "" {
			name = server.ID
		}
		cfg := sdkconfig.MCPServerConfig{
			Env:     server.Env,
			Headers: server.Headers,
		}
		switch server.ConnectionType {
		case "process", "stdio":
			cfg.Type = "stdio"
			cfg.Command = server.Command
			cfg.Args = append([]string(nil), server.Args...)
		default:
			cfg.Type = "http"
			cfg.URL = runtimeMCPURL(server.URL)
		}
		configs[name] = cfg
	}
	if len(configs) == 0 {
		return nil
	}
	return &sdkconfig.MCPConfig{Servers: configs}
}

func runtimeMCPURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed == nil {
		return raw
	}
	scheme := strings.ToLower(parsed.Scheme)
	if strings.Contains(scheme, "+") {
		return raw
	}
	if scheme != "http" && scheme != "https" {
		return raw
	}
	parsed.Scheme = scheme + "+stream"
	return parsed.String()
}

func convertStreamEvent(event sdkapi.StreamEvent) []models.TaskEvent {
	switch event.Type {
	case sdkapi.EventToolExecutionStart:
		return []models.TaskEvent{{
			Type: "tool.pending",
			Payload: map[string]interface{}{
				"tool_id":   event.ToolUseID,
				"tool_name": event.Name,
			},
		}}
	case sdkapi.EventToolExecutionOutput:
		if strings.TrimSpace(fmt.Sprint(event.Output)) == "" {
			return nil
		}
		return []models.TaskEvent{{
			Type: "tool.output",
			Payload: map[string]interface{}{
				"tool_id":   event.ToolUseID,
				"tool_name": event.Name,
				"output":    event.Output,
			},
		}}
	case sdkapi.EventToolExecutionResult:
		payload := map[string]interface{}{
			"tool_id":   event.ToolUseID,
			"tool_name": event.Name,
		}
		status := "tool.complete"
		if output, ok := toolOutputValue(event.Output); ok {
			payload["output"] = output
		}
		if toolErr := toolErrorValue(event.Output); toolErr != "" {
			status = "tool.failed"
			payload["error"] = map[string]interface{}{"message": toolErr}
		}
		return []models.TaskEvent{{Type: status, Payload: payload}}
	case sdkapi.EventError:
		return []models.TaskEvent{{
			Type: "error",
			Payload: map[string]interface{}{
				"message": defaultString(fmt.Sprint(event.Output), "runtime error"),
			},
		}}
	default:
		taskEvent := models.TaskEvent{
			Type:         event.Type,
			Message:      toMap(event.Message),
			Index:        event.Index,
			ContentBlock: contentBlockMap(event.ContentBlock),
			Delta:        deltaMap(event.Delta, event.Usage),
		}
		if len(taskEvent.Message) == 0 {
			taskEvent.Message = nil
		}
		if len(taskEvent.ContentBlock) == 0 {
			taskEvent.ContentBlock = nil
		}
		if len(taskEvent.Delta) == 0 {
			taskEvent.Delta = nil
		}
		return []models.TaskEvent{taskEvent}
	}
}

func toMap(value interface{}) map[string]interface{} {
	if value == nil {
		return nil
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return nil
	}
	out := map[string]interface{}{}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil
	}
	return out
}

func contentBlockMap(block *sdkapi.ContentBlock) map[string]interface{} {
	if block == nil {
		return nil
	}
	out := map[string]interface{}{
		"type": block.Type,
	}
	if block.Text != "" {
		out["text"] = block.Text
	}
	if block.ID != "" {
		out["id"] = block.ID
	}
	if block.Name != "" {
		out["name"] = block.Name
	}
	if len(block.Input) > 0 {
		out["input"] = decodeRawJSON(block.Input)
	}
	return out
}

func deltaMap(delta *sdkapi.Delta, usage *sdkapi.Usage) map[string]interface{} {
	out := map[string]interface{}{}
	if delta != nil {
		if delta.Type != "" {
			out["type"] = delta.Type
		}
		if delta.Text != "" {
			out["text"] = delta.Text
		}
		if len(delta.PartialJSON) > 0 {
			out["partial_json"] = decodeRawJSON(delta.PartialJSON)
		}
		if delta.StopReason != "" {
			out["stop_reason"] = delta.StopReason
		}
	}
	if usage != nil && (usage.InputTokens > 0 || usage.OutputTokens > 0) {
		out["usage"] = map[string]interface{}{
			"input_tokens":  usage.InputTokens,
			"output_tokens": usage.OutputTokens,
		}
	}
	return out
}

func decodeRawJSON(raw json.RawMessage) interface{} {
	if len(raw) == 0 {
		return nil
	}
	var decoded interface{}
	if err := json.Unmarshal(raw, &decoded); err == nil {
		return decoded
	}
	return string(raw)
}

type runtimeContextTool struct {
	skills []string
	mcps   []string
}

func newRuntimeContextTool(entries []skillcatalog.Entry, servers []models.MCPServer) sdktool.Tool {
	skillNames := make([]string, 0, len(entries))
	for _, entry := range entries {
		skillNames = append(skillNames, entry.Folder)
	}
	mcpNames := make([]string, 0, len(servers))
	for _, server := range servers {
		if server.Enabled {
			mcpNames = append(mcpNames, server.Name)
		}
	}
	sort.Strings(skillNames)
	sort.Strings(mcpNames)
	return &runtimeContextTool{skills: skillNames, mcps: mcpNames}
}

func (t *runtimeContextTool) Name() string { return "runtime_context" }
func (t *runtimeContextTool) Description() string {
	return "Summarize enabled Opendataagent skills and MCP servers."
}
func (t *runtimeContextTool) Schema() *sdktool.JSONSchema {
	return &sdktool.JSONSchema{Type: "object"}
}
func (t *runtimeContextTool) Execute(context.Context, map[string]interface{}) (*sdktool.ToolResult, error) {
	payload := map[string]interface{}{
		"skills":      t.skills,
		"mcp_servers": t.mcps,
	}
	output := fmt.Sprintf("enabled_skills=%s\nenabled_mcp_servers=%s", joinOrNone(t.skills), joinOrNone(t.mcps))
	return &sdktool.ToolResult{Success: true, Output: output, Data: payload}, nil
}

type mockModel struct {
	skillEntries []skillcatalog.Entry
	mcpServers   []models.MCPServer
}

func (m *mockModel) Complete(_ context.Context, req sdkmodel.Request) (*sdkmodel.Response, error) {
	prompt := latestPrompt(req.Messages)
	if len(toolResults(req.Messages)) == 0 {
		calls := []sdkmodel.ToolCall{{
			ID:        "runtime-context",
			Name:      "runtime_context",
			Arguments: map[string]interface{}{},
		}}
		for _, skillName := range inferSkillCalls(prompt, m.skillEntries) {
			calls = append(calls, sdkmodel.ToolCall{
				ID:        "skill-" + skillName,
				Name:      "skill",
				Arguments: map[string]interface{}{"command": skillName},
			})
		}
		return &sdkmodel.Response{
			Message:    sdkmodel.Message{Role: "assistant", ToolCalls: calls},
			Usage:      sdkmodel.Usage{InputTokens: maxInt(16, len(prompt)/2), OutputTokens: 16},
			StopReason: "tool_use",
		}, nil
	}

	final := buildMockFinal(prompt, toolResults(req.Messages))
	return &sdkmodel.Response{
		Message: sdkmodel.Message{Role: "assistant", Content: final},
		Usage: sdkmodel.Usage{
			InputTokens:  maxInt(16, len(prompt)/2),
			OutputTokens: maxInt(12, len(final)/2),
		},
		StopReason: "end_turn",
	}, nil
}

func (m *mockModel) CompleteStream(ctx context.Context, req sdkmodel.Request, cb sdkmodel.StreamHandler) error {
	resp, err := m.Complete(ctx, req)
	if err != nil {
		return err
	}
	return cb(sdkmodel.StreamResult{Final: true, Response: resp})
}

func latestPrompt(messages []sdkmodel.Message) string {
	for idx := len(messages) - 1; idx >= 0; idx-- {
		if messages[idx].Role == "user" {
			return messages[idx].TextContent()
		}
	}
	return ""
}

func inferSkillCalls(prompt string, entries []skillcatalog.Entry) []string {
	lower := strings.ToLower(strings.TrimSpace(prompt))
	if lower == "" {
		return nil
	}
	seen := map[string]struct{}{}
	out := []string{}
	for _, entry := range entries {
		name := normalizeSkillName(entry)
		if name == "" {
			continue
		}
		if strings.Contains(lower, strings.ToLower(entry.Folder)) || strings.Contains(lower, strings.ToLower(entry.Name)) {
			if _, ok := seen[name]; ok {
				continue
			}
			seen[name] = struct{}{}
			out = append(out, name)
		}
	}
	sort.Strings(out)
	return out
}

func toolResults(messages []sdkmodel.Message) map[string][]string {
	out := map[string][]string{}
	for _, msg := range messages {
		if msg.Role != "tool" {
			continue
		}
		for _, call := range msg.ToolCalls {
			out[call.Name] = append(out[call.Name], strings.TrimSpace(call.Result))
		}
	}
	return out
}

func buildMockFinal(prompt string, results map[string][]string) string {
	if strings.Contains(strings.ToLower(prompt), "smoke-ok") {
		return "smoke-ok"
	}
	lines := []string{"当前运行时已经可用。"}
	if items := results["runtime_context"]; len(items) > 0 && strings.TrimSpace(items[0]) != "" {
		lines = append(lines, items[0])
	}
	if items := results["skill"]; len(items) > 0 {
		lines = append(lines, strings.Join(items, "\n\n"))
	}
	if len(lines) == 1 {
		lines = append(lines, "本轮没有触发额外 Skill。")
	}
	lines = append(lines, "你的问题是："+strings.TrimSpace(prompt))
	return strings.Join(lines, "\n\n")
}

func normalizeSkillName(entry skillcatalog.Entry) string {
	if value := strings.TrimSpace(strings.ToLower(stringValue(entry.Frontmatter["skill_key"]))); value != "" {
		return sanitizeSkillName(value)
	}
	if value := strings.TrimSpace(strings.ToLower(entry.Folder)); value != "" {
		return sanitizeSkillName(value)
	}
	return sanitizeSkillName(strings.ToLower(entry.Name))
}

func sanitizeSkillName(value string) string {
	var builder strings.Builder
	lastDash := false
	for _, r := range strings.ToLower(strings.TrimSpace(value)) {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9':
			builder.WriteRune(r)
			lastDash = false
		case !lastDash:
			builder.WriteByte('-')
			lastDash = true
		}
	}
	return strings.Trim(builder.String(), "-")
}

type collector struct {
	blocks      []*models.MessageBlock
	blockIndex  map[string]*models.MessageBlock
	toolInput   map[string]string
	toolBlocks  map[string]string
	textParsers map[string]*textStreamParser
	usage       map[string]interface{}
	mainText    strings.Builder
	messageSeq  int
}

type textStreamParser struct {
	mode                string
	buffer              string
	justClosedReasoning bool
}

func newCollector() *collector {
	return &collector{
		blockIndex:  map[string]*models.MessageBlock{},
		toolInput:   map[string]string{},
		toolBlocks:  map[string]string{},
		textParsers: map[string]*textStreamParser{},
		usage:       map[string]interface{}{},
	}
}

func (c *collector) Apply(event models.TaskEvent) {
	switch event.Type {
	case sdkapi.EventMessageStart:
		c.messageSeq++
	case sdkapi.EventContentBlockStart:
		contentType := stringValue(event.ContentBlock["type"])
		switch contentType {
		case "text":
			c.ensureTextParser(c.blockID(event))
		case "tool_use", "server_tool_use":
			block := c.ensureToolBlock(c.blockID(event), stringValue(event.ContentBlock["id"]), defaultString(stringValue(event.ContentBlock["name"]), "Tool"))
			if block.Tool != nil && strings.TrimSpace(block.Tool.ID) != "" {
				c.toolBlocks[block.Tool.ID] = block.BlockID
			}
			if input, ok := event.ContentBlock["input"]; ok {
				block.Tool.Input = input
			}
		}
	case sdkapi.EventContentBlockDelta:
		blockID := c.blockID(event)
		block := c.blockIndex[blockID]
		deltaType := stringValue(event.Delta["type"])
		switch deltaType {
		case "text_delta":
			c.appendTextDelta(blockID, rawStringValue(event.Delta["text"]))
		case "input_json_delta":
			if block == nil {
				block = c.ensureToolBlock(blockID, "", "Tool")
			}
			chunk := rawStringValue(event.Delta["partial_json"])
			c.toolInput[block.BlockID] += chunk
			if parsed := parseMaybeJSON(c.toolInput[block.BlockID]); parsed != nil {
				block.Tool.Input = parsed
			} else {
				block.Tool.Input = c.toolInput[block.BlockID]
			}
		}
	case sdkapi.EventContentBlockStop:
		blockID := c.blockID(event)
		c.flushTextParser(blockID)
		c.completeTextBlocks(blockID)
		if block := c.blockIndex[blockID]; block != nil && block.Type != "tool" && block.Status != "failed" {
			block.Status = "success"
		}
	case "tool.pending":
		payload := event.Payload
		block := c.ensureToolBlock(c.resolveToolBlockID(payload, event), stringValue(payload["tool_id"]), defaultString(stringValue(payload["tool_name"]), "Tool"))
		block.Status = "pending"
		block.Tool.Status = "pending"
	case "tool.output":
		payload := event.Payload
		block := c.ensureToolBlock(c.resolveToolBlockID(payload, event), stringValue(payload["tool_id"]), defaultString(stringValue(payload["tool_name"]), "Tool"))
		block.Status = "streaming"
		block.Tool.Status = "streaming"
		block.Tool.Output = appendOutput(block.Tool.Output, payload["output"])
	case "tool.complete":
		payload := event.Payload
		block := c.ensureToolBlock(c.resolveToolBlockID(payload, event), stringValue(payload["tool_id"]), defaultString(stringValue(payload["tool_name"]), "Tool"))
		block.Status = "success"
		block.Tool.Status = "success"
		if input, ok := payload["input"]; ok {
			block.Tool.Input = input
		}
		if output, ok := payload["output"]; ok {
			block.Tool.Output = appendOutput(block.Tool.Output, output)
		}
	case "tool.failed":
		payload := event.Payload
		block := c.ensureToolBlock(c.resolveToolBlockID(payload, event), stringValue(payload["tool_id"]), defaultString(stringValue(payload["tool_name"]), "Tool"))
		block.Status = "failed"
		block.Tool.Status = "failed"
		if output, ok := payload["output"]; ok {
			block.Tool.Output = appendOutput(block.Tool.Output, output)
		} else if errPayload, ok := payload["error"]; ok {
			block.Tool.Output = errPayload
		}
	case sdkapi.EventMessageDelta:
		if usage, ok := event.Delta["usage"].(map[string]interface{}); ok {
			for key, value := range usage {
				c.usage[key] = value
			}
		}
	case sdkapi.EventMessageStop:
		c.flushAllTextParsers()
		for _, block := range c.blocks {
			if block == nil || block.Status == "failed" {
				continue
			}
			if block.Type == "tool" && block.Tool != nil && block.Tool.Status != "failed" && block.Tool.Status != "success" {
				block.Tool.Status = "success"
			}
			block.Status = "success"
		}
	case "error":
		block := &models.MessageBlock{
			BlockID: fmt.Sprintf("error_%d", len(c.blocks)+1),
			Type:    "error",
			Status:  "failed",
			Text:    defaultString(stringValue(event.Payload["message"]), "runtime error"),
		}
		c.blocks = append(c.blocks, block)
		c.blockIndex[block.BlockID] = block
	}
}

func (c *collector) resolveToolBlockID(payload map[string]interface{}, event models.TaskEvent) string {
	toolID := stringValue(payload["tool_id"])
	if mapped := c.toolBlocks[toolID]; mapped != "" {
		return mapped
	}
	return defaultString(toolID, c.blockID(event))
}

func (c *collector) Result() TaskResult {
	blocks := make([]models.MessageBlock, 0, len(c.blocks))
	for _, block := range c.blocks {
		if block == nil {
			continue
		}
		copyBlock := *block
		if copyBlock.Tool != nil {
			toolCopy := *copyBlock.Tool
			if toolCopy.Status == "" {
				toolCopy.Status = copyBlock.Status
			}
			copyBlock.Tool = &toolCopy
		}
		if copyBlock.Status == "" {
			copyBlock.Status = "success"
		}
		blocks = append(blocks, copyBlock)
	}
	blocks = normalizeReasoningEnvelopeBlocks(blocks)
	final, _ := agent.StripReasoningEnvelope(strings.TrimSpace(c.mainText.String()))
	final = strings.TrimSpace(final)
	if final == "" {
		for _, block := range blocks {
			if block.Type == "main_text" && strings.TrimSpace(block.Text) != "" {
				final = strings.TrimSpace(block.Text)
				break
			}
		}
	}
	return TaskResult{
		Final:  final,
		Usage:  cloneMap(c.usage),
		Blocks: blocks,
	}
}

func normalizeReasoningEnvelopeBlocks(blocks []models.MessageBlock) []models.MessageBlock {
	if len(blocks) == 0 {
		return nil
	}
	existingThinking := map[string]struct{}{}
	for _, block := range blocks {
		if block.Type == "thinking" {
			existingThinking[block.BlockID] = struct{}{}
		}
	}
	normalized := make([]models.MessageBlock, 0, len(blocks))
	for _, block := range blocks {
		if block.Type != "main_text" || !strings.Contains(block.Text, agent.ReasoningEnvelopeStart) {
			if block.Type != "main_text" || strings.TrimSpace(block.Text) != "" {
				normalized = append(normalized, block)
			}
			continue
		}
		cleaned, reasoning := agent.StripReasoningEnvelope(block.Text)
		thinkingID := block.BlockID + ":thinking"
		if reasoning != "" {
			if _, exists := existingThinking[thinkingID]; !exists {
				normalized = append(normalized, models.MessageBlock{
					BlockID: thinkingID,
					Type:    "thinking",
					Status:  block.Status,
					Text:    reasoning,
				})
				existingThinking[thinkingID] = struct{}{}
			}
		}
		block.Text = cleaned
		if strings.TrimSpace(block.Text) == "" {
			continue
		}
		normalized = append(normalized, block)
	}
	return normalized
}

func (c *collector) ensureTextBlock(id string) *models.MessageBlock {
	if block, ok := c.blockIndex[id]; ok {
		return block
	}
	block := &models.MessageBlock{
		BlockID: id,
		Type:    "main_text",
		Status:  "streaming",
	}
	c.blocks = append(c.blocks, block)
	c.blockIndex[id] = block
	return block
}

func (c *collector) ensureThinkingBlock(id string) *models.MessageBlock {
	if block, ok := c.blockIndex[id]; ok {
		return block
	}
	block := &models.MessageBlock{
		BlockID: id,
		Type:    "thinking",
		Status:  "streaming",
	}
	c.blocks = append(c.blocks, block)
	c.blockIndex[id] = block
	return block
}

func (c *collector) ensureToolBlock(blockID string, toolID string, toolName string) *models.MessageBlock {
	if blockID == "" {
		blockID = fmt.Sprintf("tool_%d", len(c.blocks)+1)
	}
	if block, ok := c.blockIndex[blockID]; ok {
		if block.Tool != nil {
			if strings.TrimSpace(toolID) != "" {
				block.Tool.ID = toolID
			}
			if strings.TrimSpace(toolName) != "" {
				block.Tool.Name = toolName
			}
		}
		return block
	}
	block := &models.MessageBlock{
		BlockID: blockID,
		Type:    "tool",
		Status:  "pending",
		Tool: &models.ToolPayload{
			ID:     defaultString(toolID, blockID),
			Name:   defaultString(toolName, "Tool"),
			Status: "pending",
		},
	}
	c.blocks = append(c.blocks, block)
	c.blockIndex[blockID] = block
	return block
}

func (c *collector) blockID(event models.TaskEvent) string {
	if event.Index != nil {
		messageSeq := c.messageSeq
		if messageSeq <= 0 {
			messageSeq = 1
		}
		return fmt.Sprintf("m%d-cb-%d", messageSeq, *event.Index)
	}
	if event.CorrelationID != "" {
		return event.CorrelationID
	}
	return fmt.Sprintf("evt-%d", c.messageSeq)
}

func toolOutputValue(value interface{}) (interface{}, bool) {
	payload, ok := value.(map[string]interface{})
	if !ok {
		if value == nil {
			return nil, false
		}
		return value, true
	}
	if output, exists := payload["output"]; exists {
		return output, true
	}
	return payload, true
}

func toolErrorValue(value interface{}) string {
	payload, ok := value.(map[string]interface{})
	if !ok {
		return ""
	}
	metadata, ok := payload["metadata"].(map[string]interface{})
	if !ok {
		return ""
	}
	if metadata["error"] == nil {
		return ""
	}
	return strings.TrimSpace(stringValue(metadata["error"]))
}

func appendOutput(current interface{}, next interface{}) interface{} {
	if current == nil {
		return next
	}
	currentText, currentOK := current.(string)
	nextText, nextOK := next.(string)
	if currentOK && nextOK {
		if currentText == "" {
			return nextText
		}
		if strings.HasSuffix(currentText, nextText) || nextText == "" {
			return currentText
		}
		return currentText + nextText
	}
	return next
}

func (c *collector) ensureTextParser(blockID string) *textStreamParser {
	if parser, ok := c.textParsers[blockID]; ok {
		return parser
	}
	parser := &textStreamParser{}
	c.textParsers[blockID] = parser
	return parser
}

func (c *collector) appendTextDelta(blockID string, chunk string) {
	parser := c.ensureTextParser(blockID)
	parser.buffer += chunk
	c.drainTextParser(blockID, false)
}

func (c *collector) flushTextParser(blockID string) {
	if _, ok := c.textParsers[blockID]; !ok {
		return
	}
	c.drainTextParser(blockID, true)
	delete(c.textParsers, blockID)
}

func (c *collector) flushAllTextParsers() {
	ids := make([]string, 0, len(c.textParsers))
	for blockID := range c.textParsers {
		ids = append(ids, blockID)
	}
	sort.Strings(ids)
	for _, blockID := range ids {
		c.flushTextParser(blockID)
		c.completeTextBlocks(blockID)
	}
}

func (c *collector) drainTextParser(blockID string, flush bool) {
	parser := c.ensureTextParser(blockID)
	for {
		if parser.mode == "thinking" {
			if idx := strings.Index(parser.buffer, agent.ReasoningEnvelopeEnd); idx >= 0 {
				c.appendThinkingText(blockID, parser.buffer[:idx])
				parser.buffer = parser.buffer[idx+len(agent.ReasoningEnvelopeEnd):]
				parser.mode = ""
				parser.justClosedReasoning = true
				continue
			}
			if !c.emitPartialText(blockID, parser, agent.ReasoningEnvelopeEnd, flush, true) {
				return
			}
			continue
		}
		if parser.justClosedReasoning {
			parser.buffer = strings.TrimPrefix(parser.buffer, "\n\n")
			parser.buffer = strings.TrimPrefix(parser.buffer, "\n")
			if parser.buffer == "" && !flush {
				return
			}
			parser.justClosedReasoning = false
		}
		if idx := strings.Index(parser.buffer, agent.ReasoningEnvelopeStart); idx >= 0 {
			c.appendMainText(blockID, parser.buffer[:idx])
			parser.buffer = parser.buffer[idx+len(agent.ReasoningEnvelopeStart):]
			parser.mode = "thinking"
			continue
		}
		if !c.emitPartialText(blockID, parser, agent.ReasoningEnvelopeStart, flush, false) {
			return
		}
	}
}

func (c *collector) emitPartialText(blockID string, parser *textStreamParser, marker string, flush bool, thinking bool) bool {
	keep := markerPrefixCarry(parser.buffer, marker)
	if flush || len(parser.buffer) <= keep {
		if flush && parser.buffer != "" {
			if thinking {
				c.appendThinkingText(blockID, parser.buffer)
			} else {
				c.appendMainText(blockID, parser.buffer)
			}
			parser.buffer = ""
		}
		return false
	}
	emit := parser.buffer[:len(parser.buffer)-keep]
	parser.buffer = parser.buffer[len(parser.buffer)-keep:]
	if thinking {
		c.appendThinkingText(blockID, emit)
	} else {
		c.appendMainText(blockID, emit)
	}
	return false
}

func (c *collector) appendMainText(blockID string, text string) {
	if text == "" {
		return
	}
	block := c.ensureTextBlock(blockID)
	block.Text += text
	c.mainText.WriteString(text)
}

func (c *collector) appendThinkingText(blockID string, text string) {
	if text == "" {
		return
	}
	block := c.ensureThinkingBlock(blockID + ":thinking")
	block.Text += text
}

func (c *collector) completeTextBlocks(blockID string) {
	for _, id := range []string{blockID, blockID + ":thinking"} {
		if block := c.blockIndex[id]; block != nil && block.Type != "tool" && block.Status != "failed" {
			block.Status = "success"
		}
	}
}

func parseMaybeJSON(value string) interface{} {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return nil
	}
	var decoded interface{}
	if err := json.Unmarshal([]byte(trimmed), &decoded); err != nil {
		return nil
	}
	return decoded
}

func cloneMap(value map[string]interface{}) map[string]interface{} {
	if len(value) == 0 {
		return nil
	}
	out := make(map[string]interface{}, len(value))
	for key, item := range value {
		out[key] = item
	}
	return out
}

func joinOrNone(items []string) string {
	if len(items) == 0 {
		return "none"
	}
	return strings.Join(items, ", ")
}

func defaultString(value string, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func stringValue(value interface{}) string {
	if value == nil {
		return ""
	}
	text, ok := value.(string)
	if ok {
		return strings.TrimSpace(text)
	}
	return strings.TrimSpace(fmt.Sprint(value))
}

func rawStringValue(value interface{}) string {
	if value == nil {
		return ""
	}
	if text, ok := value.(string); ok {
		return text
	}
	return fmt.Sprint(value)
}

func boolPtr(value bool) *bool {
	return &value
}

func maxInt(a int, b int) int {
	if a > b {
		return a
	}
	return b
}

func minInt(a int, b int) int {
	if a < b {
		return a
	}
	return b
}

func markerPrefixCarry(buffer string, marker string) int {
	maxKeep := minInt(len(buffer), len(marker)-1)
	for keep := maxKeep; keep > 0; keep-- {
		if strings.HasPrefix(marker, buffer[len(buffer)-keep:]) {
			return keep
		}
	}
	return 0
}

func getenv(key string, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func envBool(key string) bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(key))) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}
