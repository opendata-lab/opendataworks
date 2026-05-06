package app

import (
	"context"
	"errors"
	"log"
	"os"
	"path/filepath"
	"sort"
	"sync"

	agentcfg "opendataagent/server/internal/agent"
	"opendataagent/server/internal/config"
	"opendataagent/server/internal/models"
	"opendataagent/server/internal/runtime"
	"opendataagent/server/internal/skills"
	"opendataagent/server/internal/store"
	"opendataagent/server/internal/util"
)

type App struct {
	cfg         config.Config
	store       store.Store
	mu          sync.RWMutex
	state       models.StateSnapshot
	skillSvc    skills.Service
	subscribers map[string]map[chan models.TaskEvent]struct{}
	taskCancels map[string]context.CancelFunc
}

func New(cfg config.Config) (*App, error) {
	if err := os.MkdirAll(cfg.StateDir, 0755); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cfg.SkillsDir, 0755); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(cfg.ManagedSkillsDir, 0755); err != nil {
		return nil, err
	}
	stateStore, err := store.New(cfg)
	if err != nil {
		return nil, err
	}

	app := &App{
		cfg:   cfg,
		store: stateStore,
		skillSvc: skills.Service{
			BundledDir: cfg.BundledSkillsDir,
			ManagedDir: cfg.ManagedSkillsDir,
		},
		subscribers: make(map[string]map[chan models.TaskEvent]struct{}),
		taskCancels: make(map[string]context.CancelFunc),
	}
	if err := app.skillSvc.SyncSharedSource(cfg.SharedSkillsDir, cfg.SkillsDir); err != nil {
		_ = stateStore.Close()
		return nil, err
	}
	app.state = app.defaultState()
	if err := app.load(); err != nil {
		_ = stateStore.Close()
		return nil, err
	}
	if err := app.SyncSkills(); err != nil {
		_ = stateStore.Close()
		return nil, err
	}
	return app, nil
}

func (a *App) defaultState() models.StateSnapshot {
	now := util.Now()
	settings := models.AgentSettings{
		DefaultProviderID: agentcfg.DefaultProviderID(),
		DefaultModel:      agentcfg.DefaultModel(),
		ProviderID:        agentcfg.DefaultProviderID(),
		Model:             agentcfg.DefaultModel(),
		ManagedSkillsDir:  a.cfg.ManagedSkillsDir,
		SkillsRootDir:     a.cfg.BundledSkillsDir,
		SessionMySQLDB:    "oda_local",
		AdminToken:        a.cfg.AdminToken,
		Providers:         agentcfg.DefaultProviderCatalog(),
		UpdatedAt:         now,
	}
	settings = agentcfg.NormalizeSettings(settings)
	settings.ManagedSkillsDir = a.cfg.ManagedSkillsDir
	settings.SkillsRootDir = a.cfg.BundledSkillsDir
	settings.SessionMySQLDB = "oda_local"
	settings.AdminToken = a.cfg.AdminToken
	settings.UpdatedAt = now
	return models.StateSnapshot{
		Settings:           settings,
		Topics:             map[string]*models.Topic{},
		Tasks:              map[string]*models.Task{},
		TaskEvents:         map[string][]models.TaskEvent{},
		SkillDocuments:     map[string]*models.SkillDocument{},
		SkillRuntime:       map[string]*models.SkillRuntimeConfig{},
		SkillInstallations: map[string]*models.SkillInstallation{},
		MCPServers:         map[string]*models.MCPServer{},
	}
}

func (a *App) load() error {
	loaded, err := a.store.Load()
	if err != nil {
		return err
	}
	if loaded.Settings.DefaultProviderID != "" {
		a.state.Settings = loaded.Settings
	}
	if loaded.Topics != nil {
		a.state.Topics = loaded.Topics
	}
	if loaded.Tasks != nil {
		a.state.Tasks = loaded.Tasks
	}
	if loaded.TaskEvents != nil {
		a.state.TaskEvents = loaded.TaskEvents
	}
	if loaded.SkillDocuments != nil {
		a.state.SkillDocuments = loaded.SkillDocuments
	}
	if loaded.SkillRuntime != nil {
		a.state.SkillRuntime = loaded.SkillRuntime
	}
	if loaded.SkillInstallations != nil {
		a.state.SkillInstallations = loaded.SkillInstallations
	}
	if loaded.MCPServers != nil {
		a.state.MCPServers = loaded.MCPServers
	}
	if a.state.Topics == nil {
		a.state.Topics = map[string]*models.Topic{}
	}
	if a.state.Tasks == nil {
		a.state.Tasks = map[string]*models.Task{}
	}
	if a.state.TaskEvents == nil {
		a.state.TaskEvents = map[string][]models.TaskEvent{}
	}
	if a.state.SkillDocuments == nil {
		a.state.SkillDocuments = map[string]*models.SkillDocument{}
	}
	if a.state.SkillRuntime == nil {
		a.state.SkillRuntime = map[string]*models.SkillRuntimeConfig{}
	}
	if a.state.SkillInstallations == nil {
		a.state.SkillInstallations = map[string]*models.SkillInstallation{}
	}
	if a.state.MCPServers == nil {
		a.state.MCPServers = map[string]*models.MCPServer{}
	}
	if a.state.Settings.ManagedSkillsDir == "" {
		a.state.Settings.ManagedSkillsDir = a.cfg.ManagedSkillsDir
	}
	if a.state.Settings.SkillsRootDir == "" {
		a.state.Settings.SkillsRootDir = a.cfg.BundledSkillsDir
	}
	if a.state.Settings.AdminToken == "" {
		a.state.Settings.AdminToken = a.cfg.AdminToken
	}
	if a.state.Settings.SessionMySQLDB == "" {
		a.state.Settings.SessionMySQLDB = "oda_local"
	}
	a.state.Settings = agentcfg.NormalizeSettings(a.state.Settings)
	a.skillSvc.BundledDir = a.state.Settings.SkillsRootDir
	a.skillSvc.ManagedDir = a.state.Settings.ManagedSkillsDir
	return nil
}

func (a *App) saveLocked() error {
	return a.store.Save(a.state)
}

func (a *App) GetSettings() models.AgentSettings {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.state.Settings
}

func (a *App) AdminToken() string {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.state.Settings.AdminToken
}

func (a *App) UpdateSettings(next models.AgentSettings) (models.AgentSettings, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	settings := a.state.Settings
	if next.DefaultProviderID != "" {
		settings.DefaultProviderID = next.DefaultProviderID
		settings.ProviderID = next.DefaultProviderID
	}
	if next.DefaultModel != "" {
		settings.DefaultModel = next.DefaultModel
		settings.Model = next.DefaultModel
	}
	if next.ProviderID != "" {
		settings.ProviderID = next.ProviderID
	}
	if next.Model != "" {
		settings.Model = next.Model
	}
	if next.ManagedSkillsDir != "" {
		settings.ManagedSkillsDir = next.ManagedSkillsDir
		a.cfg.ManagedSkillsDir = next.ManagedSkillsDir
		a.skillSvc.ManagedDir = next.ManagedSkillsDir
		_ = os.MkdirAll(next.ManagedSkillsDir, 0755)
	}
	if next.SkillsRootDir != "" {
		settings.SkillsRootDir = next.SkillsRootDir
		a.cfg.BundledSkillsDir = next.SkillsRootDir
		a.skillSvc.BundledDir = next.SkillsRootDir
	}
	if next.AdminToken != "" {
		settings.AdminToken = next.AdminToken
	}
	if next.SessionMySQLDB != "" {
		settings.SessionMySQLDB = next.SessionMySQLDB
	}
	if len(next.Providers) > 0 {
		settings.Providers = next.Providers
	}
	settings = agentcfg.NormalizeSettings(settings)
	settings.UpdatedAt = util.Now()
	a.state.Settings = settings
	return settings, a.saveLocked()
}

func (a *App) ListTopics() []models.Topic {
	a.mu.RLock()
	defer a.mu.RUnlock()
	items := make([]models.Topic, 0, len(a.state.Topics))
	for _, item := range a.state.Topics {
		copyItem := *item
		copyItem.Messages = nil
		items = append(items, copyItem)
	}
	sort.Slice(items, func(i, j int) bool { return items[i].UpdatedAt > items[j].UpdatedAt })
	return items
}

func (a *App) CreateTopic(title string) (models.Topic, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if title == "" {
		title = "新话题"
	}
	now := util.Now()
	topic := &models.Topic{
		TopicID:      util.NewID("topic"),
		Title:        title,
		CreatedAt:    now,
		UpdatedAt:    now,
		Messages:     []models.Message{},
		MessageCount: 0,
	}
	a.state.Topics[topic.TopicID] = topic
	return *topic, a.saveLocked()
}

func (a *App) GetTopic(topicID string) (models.Topic, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	topic, ok := a.state.Topics[topicID]
	if !ok {
		return models.Topic{}, errors.New("topic not found")
	}
	copyItem := *topic
	copyItem.Messages = nil
	return copyItem, nil
}

func (a *App) UpdateTopic(topicID string, req models.UpdateTopicRequest) (models.Topic, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	topic, ok := a.state.Topics[topicID]
	if !ok {
		return models.Topic{}, errors.New("topic not found")
	}
	if req.Title != "" {
		topic.Title = req.Title
	}
	topic.UpdatedAt = util.Now()
	return *topic, a.saveLocked()
}

func (a *App) DeleteTopic(topicID string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, ok := a.state.Topics[topicID]; !ok {
		return errors.New("topic not found")
	}
	for taskID, task := range a.state.Tasks {
		if task == nil || task.TopicID != topicID {
			continue
		}
		if cancel := a.taskCancels[taskID]; cancel != nil {
			cancel()
		}
		delete(a.taskCancels, taskID)
		delete(a.state.Tasks, taskID)
		delete(a.state.TaskEvents, taskID)
		delete(a.subscribers, taskID)
	}
	delete(a.state.Topics, topicID)
	return a.saveLocked()
}

func (a *App) ListTopicMessages(topicID string) ([]models.Message, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	topic, ok := a.state.Topics[topicID]
	if !ok {
		return nil, errors.New("topic not found")
	}
	out := make([]models.Message, len(topic.Messages))
	copy(out, topic.Messages)
	return out, nil
}

func (a *App) DeliverMessage(req models.DeliverMessageRequest) (map[string]interface{}, error) {
	a.mu.Lock()
	topic, ok := a.state.Topics[req.TopicID]
	if !ok {
		a.mu.Unlock()
		return nil, errors.New("topic not found")
	}
	selection, err := agentcfg.ResolveSelection(a.state.Settings, req.ProviderID, req.Model)
	if err != nil {
		a.mu.Unlock()
		return nil, err
	}
	now := util.Now()
	topic.LastMessageSeq++
	userMessage := models.Message{
		MessageID:  util.NewID("user"),
		TopicID:    topic.TopicID,
		MessageSeq: topic.LastMessageSeq,
		SenderType: "user",
		Content:    req.Content,
		Status:     "success",
		CreatedAt:  now,
		UpdatedAt:  now,
	}
	topic.LastMessageSeq++
	assistantMessage := models.Message{
		MessageID:  util.NewID("assistant"),
		TopicID:    topic.TopicID,
		MessageSeq: topic.LastMessageSeq,
		SenderType: "assistant",
		Status:     "waiting",
		ProviderID: selection.ProviderID,
		Model:      selection.Model,
		CreatedAt:  now,
		UpdatedAt:  now,
		Blocks:     []models.MessageBlock{},
	}
	task := &models.Task{
		TaskID:             util.NewID("task"),
		TopicID:            topic.TopicID,
		AssistantMessageID: assistantMessage.MessageID,
		Prompt:             req.Content,
		ProviderID:         assistantMessage.ProviderID,
		ModelName:          assistantMessage.Model,
		TaskStatus:         "waiting",
		CreatedAt:          now,
		UpdatedAt:          now,
	}
	assistantMessage.TaskID = task.TaskID
	topic.Messages = append(topic.Messages, userMessage, assistantMessage)
	topic.MessageCount = len(topic.Messages)
	topic.CurrentTaskID = task.TaskID
	topic.CurrentTaskStatus = task.TaskStatus
	topic.UpdatedAt = now
	a.state.Tasks[task.TaskID] = task
	_ = a.saveLocked()
	a.mu.Unlock()

	go a.runTask(task.TaskID)

	return map[string]interface{}{
		"accepted":             true,
		"assistant_message_id": assistantMessage.MessageID,
		"task_id":              task.TaskID,
		"task_status":          task.TaskStatus,
	}, nil
}

func (a *App) runTask(taskID string) {
	a.mu.Lock()
	task, ok := a.state.Tasks[taskID]
	if !ok {
		a.mu.Unlock()
		return
	}
	task.TaskStatus = "running"
	task.StartedAt = util.Now()
	task.UpdatedAt = task.StartedAt
	if topic, exists := a.state.Topics[task.TopicID]; exists {
		topic.CurrentTaskStatus = "running"
		topic.UpdatedAt = util.Now()
		for idx := range topic.Messages {
			if topic.Messages[idx].MessageID == task.AssistantMessageID {
				topic.Messages[idx].Status = "running"
				topic.Messages[idx].UpdatedAt = util.Now()
			}
		}
	}
	activeSkills := a.activeSkillEntriesLocked()
	activeMcps := a.activeMCPServersLocked()
	settings := a.state.Settings
	ctx, cancel := context.WithCancel(context.Background())
	a.taskCancels[taskID] = cancel
	_ = a.saveLocked()
	a.mu.Unlock()
	defer a.clearTaskCancel(taskID)

	log.Printf("agent task started task_id=%s topic_id=%s provider=%s model=%s", task.TaskID, task.TopicID, task.ProviderID, task.ModelName)

	modelFactory, _, err := agentcfg.CreateModelFactoryFromSettings(
		settings,
		task.ProviderID,
		task.ModelName,
		runtime.NewMockModelFactory(activeSkills, activeMcps),
	)
	if err != nil {
		log.Printf("agent task model setup failed task_id=%s provider=%s model=%s error=%v", task.TaskID, task.ProviderID, task.ModelName, err)
		a.finishTaskError(taskID, err, runtime.TaskResult{})
		return
	}

	result, err := runtime.Run(ctx, runtime.RunInput{
		Prompt:       task.Prompt,
		SessionID:    task.TopicID,
		ModelFactory: modelFactory,
		ProjectRoot:  a.cfg.ProjectRoot,
		SkillEntries: activeSkills,
		MCPServers:   activeMcps,
	}, func(event models.TaskEvent) {
		a.appendTaskEvent(taskID, event)
	})
	if err != nil {
		if errors.Is(err, context.Canceled) || a.isTaskCancelled(taskID) {
			log.Printf("agent task cancelled task_id=%s provider=%s model=%s", task.TaskID, task.ProviderID, task.ModelName)
			a.finishTaskAsCancelled(taskID)
			return
		}
		log.Printf("agent task failed task_id=%s provider=%s model=%s error=%v", task.TaskID, task.ProviderID, task.ModelName, err)
		a.finishTaskError(taskID, err, result)
		return
	}
	if a.isTaskCancelled(taskID) {
		log.Printf("agent task cancelled task_id=%s provider=%s model=%s", task.TaskID, task.ProviderID, task.ModelName)
		a.finishTaskAsCancelled(taskID)
		return
	}
	log.Printf("agent task finished task_id=%s provider=%s model=%s final_len=%d blocks=%d", task.TaskID, task.ProviderID, task.ModelName, len(result.Final), len(result.Blocks))
	a.finishTaskSuccess(taskID, result)
}

func (a *App) isTaskCancelled(taskID string) bool {
	a.mu.RLock()
	defer a.mu.RUnlock()
	task, ok := a.state.Tasks[taskID]
	return ok && task.TaskStatus == "suspended"
}

func (a *App) finishTaskAsCancelled(taskID string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	task, ok := a.state.Tasks[taskID]
	if !ok {
		return
	}
	now := util.Now()
	task.TaskStatus = "suspended"
	task.UpdatedAt = now
	task.FinishedAt = now
	if topic := a.state.Topics[task.TopicID]; topic != nil {
		topic.CurrentTaskStatus = "suspended"
		topic.UpdatedAt = now
		for idx := range topic.Messages {
			if topic.Messages[idx].MessageID == task.AssistantMessageID {
				topic.Messages[idx].Status = "suspended"
				topic.Messages[idx].UpdatedAt = now
				topic.Messages[idx].Error = &models.ErrorPayload{Code: "task_cancelled", Message: "任务已取消"}
				topic.Messages[idx].ResumeAfterSeq = task.LastEventSeq
			}
		}
	}
	_ = a.saveLocked()
}

func (a *App) finishTaskSuccess(taskID string, result runtime.TaskResult) {
	a.mu.Lock()
	defer a.mu.Unlock()
	task, ok := a.state.Tasks[taskID]
	if !ok {
		return
	}
	now := util.Now()
	task.TaskStatus = "finished"
	task.UpdatedAt = now
	task.FinishedAt = now
	if topic := a.state.Topics[task.TopicID]; topic != nil {
		topic.CurrentTaskStatus = "finished"
		topic.UpdatedAt = now
		for idx := range topic.Messages {
			if topic.Messages[idx].MessageID == task.AssistantMessageID {
				topic.Messages[idx].Status = "finished"
				topic.Messages[idx].Content = result.Final
				topic.Messages[idx].UpdatedAt = now
				topic.Messages[idx].ResumeAfterSeq = task.LastEventSeq
				topic.Messages[idx].Usage = result.Usage
				topic.Messages[idx].Blocks = result.Blocks
			}
		}
	}
	_ = a.saveLocked()
}

func (a *App) clearTaskCancel(taskID string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.taskCancels, taskID)
}

func (a *App) finishTaskError(taskID string, runErr error, result runtime.TaskResult) {
	a.mu.Lock()
	defer a.mu.Unlock()
	task, ok := a.state.Tasks[taskID]
	if !ok {
		return
	}
	now := util.Now()
	task.TaskStatus = "error"
	task.UpdatedAt = now
	task.FinishedAt = now
	task.Error = &models.ErrorPayload{Code: "runtime_error", Message: runErr.Error()}
	if topic := a.state.Topics[task.TopicID]; topic != nil {
		topic.CurrentTaskStatus = "error"
		topic.UpdatedAt = now
		for idx := range topic.Messages {
			if topic.Messages[idx].MessageID == task.AssistantMessageID {
				topic.Messages[idx].Status = "error"
				topic.Messages[idx].Content = result.Final
				topic.Messages[idx].UpdatedAt = now
				topic.Messages[idx].ResumeAfterSeq = task.LastEventSeq
				topic.Messages[idx].Usage = result.Usage
				topic.Messages[idx].Blocks = result.Blocks
				topic.Messages[idx].Error = &models.ErrorPayload{Code: "runtime_error", Message: runErr.Error()}
			}
		}
	}
	if !a.hasErrorEventLocked(taskID) {
		a.appendTaskEventLocked(taskID, models.TaskEvent{
			Type: "error",
			Payload: map[string]interface{}{
				"message": runErr.Error(),
			},
		})
	}
	_ = a.saveLocked()
}

func (a *App) appendTaskEvent(taskID string, event models.TaskEvent) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.appendTaskEventLocked(taskID, event)
	_ = a.saveLocked()
}

func (a *App) appendTaskEventLocked(taskID string, event models.TaskEvent) {
	task, ok := a.state.Tasks[taskID]
	if !ok {
		return
	}
	task.LastEventSeq++
	task.UpdatedAt = util.Now()
	event.SeqID = task.LastEventSeq
	event.TaskID = taskID
	event.MessageID = task.AssistantMessageID
	event.CreatedAt = util.Now()
	a.state.TaskEvents[taskID] = append(a.state.TaskEvents[taskID], event)
	a.broadcastLocked(taskID, event)
}

func (a *App) hasErrorEventLocked(taskID string) bool {
	for _, event := range a.state.TaskEvents[taskID] {
		if event.Type == "error" {
			return true
		}
	}
	return false
}

func (a *App) GetTask(taskID string) (models.Task, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	task, ok := a.state.Tasks[taskID]
	if !ok {
		return models.Task{}, errors.New("task not found")
	}
	return *task, nil
}

func (a *App) ListTaskEvents(taskID string, afterSeq int64) ([]models.TaskEvent, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	items := a.state.TaskEvents[taskID]
	out := []models.TaskEvent{}
	for _, item := range items {
		if item.SeqID > afterSeq {
			out = append(out, item)
		}
	}
	return out, nil
}

func (a *App) CancelTask(taskID string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	task, ok := a.state.Tasks[taskID]
	if !ok {
		return errors.New("task not found")
	}
	task.TaskStatus = "suspended"
	task.UpdatedAt = util.Now()
	if cancel := a.taskCancels[taskID]; cancel != nil {
		cancel()
		delete(a.taskCancels, taskID)
	}
	return a.saveLocked()
}

func (a *App) Subscribe(taskID string) (<-chan models.TaskEvent, func()) {
	ch := make(chan models.TaskEvent, 16)
	a.mu.Lock()
	if a.subscribers[taskID] == nil {
		a.subscribers[taskID] = map[chan models.TaskEvent]struct{}{}
	}
	a.subscribers[taskID][ch] = struct{}{}
	a.mu.Unlock()
	return ch, func() {
		a.mu.Lock()
		defer a.mu.Unlock()
		delete(a.subscribers[taskID], ch)
		close(ch)
	}
}

func (a *App) broadcastLocked(taskID string, event models.TaskEvent) {
	for ch := range a.subscribers[taskID] {
		select {
		case ch <- event:
		default:
		}
	}
}

func (a *App) SyncSkills() error {
	docs, err := a.skillSvc.BuildDocuments()
	if err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	seenDocs := make(map[string]struct{}, len(docs))
	managedDocs := make(map[string]struct{}, len(docs))
	for _, doc := range docs {
		seenDocs[doc.ID] = struct{}{}
		if doc.Source == "managed" {
			managedDocs[doc.ID] = struct{}{}
		}
		existing := a.state.SkillDocuments[doc.ID]
		if existing == nil {
			version := models.SkillDocumentVersion{
				ID:            util.NewID("skillver"),
				DocumentID:    doc.ID,
				VersionNo:     1,
				ChangeSource:  "sync",
				ChangeSummary: "initial sync",
				Actor:         "system",
				ContentHash:   util.HashText(doc.CurrentContent),
				FileSize:      len(doc.CurrentContent),
				CreatedAt:     util.Now(),
				IsCurrent:     true,
				Content:       doc.CurrentContent,
			}
			doc.CurrentVersionID = version.ID
			doc.VersionCount = 1
			doc.Versions = []models.SkillDocumentVersion{version}
			if a.state.SkillRuntime[doc.ID] == nil {
				enabled := true
				a.state.SkillRuntime[doc.ID] = &models.SkillRuntimeConfig{SkillID: doc.ID, Enabled: &enabled}
			}
			if state := a.state.SkillRuntime[doc.ID]; state != nil && state.Enabled != nil {
				doc.Enabled = *state.Enabled
			}
			item := doc
			a.state.SkillDocuments[doc.ID] = &item
			continue
		}
		existing.Source = doc.Source
		existing.Category = doc.Category
		existing.RelativePath = doc.RelativePath
		existing.FileName = doc.FileName
		existing.CurrentContent = doc.CurrentContent
		existing.CurrentHash = util.HashText(doc.CurrentContent)
		existing.Editable = doc.Editable
		existing.Metadata = doc.Metadata
		existing.UpdatedAt = util.Now()
		if a.state.SkillRuntime[doc.ID] == nil {
			enabled := true
			a.state.SkillRuntime[doc.ID] = &models.SkillRuntimeConfig{SkillID: doc.ID, Enabled: &enabled}
		}
		if state := a.state.SkillRuntime[doc.ID]; state != nil && state.Enabled != nil {
			existing.Enabled = *state.Enabled
		}
	}
	for documentID := range a.state.SkillDocuments {
		if _, ok := seenDocs[documentID]; ok {
			continue
		}
		delete(a.state.SkillDocuments, documentID)
		delete(a.state.SkillRuntime, documentID)
	}
	for folder := range a.state.SkillInstallations {
		if _, ok := managedDocs[folder]; ok {
			continue
		}
		delete(a.state.SkillInstallations, folder)
	}
	return a.saveLocked()
}

func (a *App) ListSkillDocuments() []models.SkillDocument {
	a.mu.RLock()
	defer a.mu.RUnlock()
	items := make([]models.SkillDocument, 0, len(a.state.SkillDocuments))
	for _, doc := range a.state.SkillDocuments {
		copyDoc := *doc
		copyDoc.CurrentContent = ""
		copyDoc.Versions = nil
		if state := a.state.SkillRuntime[doc.ID]; state != nil && state.Enabled != nil {
			copyDoc.Enabled = *state.Enabled
		}
		items = append(items, copyDoc)
	}
	sort.Slice(items, func(i, j int) bool { return items[i].RelativePath < items[j].RelativePath })
	return items
}

func (a *App) GetSkillDocument(documentID string) (models.SkillDocument, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	doc, ok := a.state.SkillDocuments[documentID]
	if !ok {
		return models.SkillDocument{}, errors.New("skill document not found")
	}
	copyDoc := *doc
	if state := a.state.SkillRuntime[doc.ID]; state != nil && state.Enabled != nil {
		copyDoc.Enabled = *state.Enabled
	}
	return copyDoc, nil
}

func (a *App) UpdateSkillDocument(documentID string, req models.UpdateSkillDocumentRequest) (models.SkillDocument, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	doc, ok := a.state.SkillDocuments[documentID]
	if !ok {
		return models.SkillDocument{}, errors.New("skill document not found")
	}
	targetDir := filepath.Join(a.cfg.ManagedSkillsDir, doc.Folder)
	if _, err := os.Stat(filepath.Join(targetDir, "SKILL.md")); err != nil {
		if _, err := a.skillSvc.EnsureManagedSkill(doc.Folder, a.cfg.BundledSkillsDir); err != nil {
			return models.SkillDocument{}, err
		}
		doc.Source = "managed"
		doc.Editable = true
	}
	content := req.Content
	if err := os.WriteFile(filepath.Join(targetDir, "SKILL.md"), []byte(content), 0644); err != nil {
		return models.SkillDocument{}, err
	}
	for idx := range doc.Versions {
		doc.Versions[idx].IsCurrent = false
	}
	version := models.SkillDocumentVersion{
		ID:              util.NewID("skillver"),
		DocumentID:      doc.ID,
		VersionNo:       len(doc.Versions) + 1,
		ChangeSource:    "ui",
		ChangeSummary:   defaultString(req.ChangeSummary, "前端保存"),
		Actor:           "admin",
		ContentHash:     util.HashText(content),
		FileSize:        len(content),
		ParentVersionID: doc.CurrentVersionID,
		CreatedAt:       util.Now(),
		IsCurrent:       true,
		Content:         content,
	}
	doc.CurrentContent = content
	doc.CurrentHash = version.ContentHash
	doc.CurrentVersionID = version.ID
	doc.VersionCount = len(doc.Versions) + 1
	doc.LastChangeSource = version.ChangeSource
	doc.LastChangeSummary = version.ChangeSummary
	doc.UpdatedAt = version.CreatedAt
	doc.Source = "managed"
	doc.Editable = true
	doc.Versions = append(doc.Versions, version)
	if a.state.SkillInstallations[doc.Folder] == nil {
		a.state.SkillInstallations[doc.Folder] = &models.SkillInstallation{
			ID:          util.NewID("install"),
			ItemID:      doc.Folder,
			Folder:      doc.Folder,
			Source:      "bundled",
			InstalledAt: util.Now(),
		}
	}
	return *doc, a.saveLocked()
}

func (a *App) DeleteSkillDocument(documentID string) error {
	a.mu.RLock()
	doc, ok := a.state.SkillDocuments[documentID]
	if !ok {
		a.mu.RUnlock()
		return errors.New("skill document not found")
	}
	if doc.Source != "managed" {
		a.mu.RUnlock()
		return errors.New("only managed skill can be deleted")
	}
	folder := doc.Folder
	a.mu.RUnlock()

	if err := a.skillSvc.DeleteManagedSkill(folder); err != nil {
		return err
	}

	a.mu.Lock()
	delete(a.state.SkillDocuments, documentID)
	delete(a.state.SkillInstallations, folder)
	delete(a.state.SkillRuntime, folder)
	if err := a.saveLocked(); err != nil {
		a.mu.Unlock()
		return err
	}
	a.mu.Unlock()

	return a.SyncSkills()
}

func (a *App) UpdateSkillRuntime(skillID string, enabled bool) (models.SkillRuntimeConfig, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.state.SkillDocuments[skillID] == nil {
		return models.SkillRuntimeConfig{}, errors.New("skill document not found")
	}
	cfg := a.state.SkillRuntime[skillID]
	if cfg == nil {
		cfg = &models.SkillRuntimeConfig{SkillID: skillID}
		a.state.SkillRuntime[skillID] = cfg
	}
	cfg.SkillID = skillID
	cfg.Enabled = boolPtr(enabled)
	if doc := a.state.SkillDocuments[skillID]; doc != nil {
		doc.Enabled = enabled
		doc.UpdatedAt = util.Now()
	}
	if err := a.saveLocked(); err != nil {
		return models.SkillRuntimeConfig{}, err
	}
	return *cfg, nil
}

func (a *App) CompareSkillDocument(documentID string, req models.CompareSkillDocumentRequest) (map[string]interface{}, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	doc, ok := a.state.SkillDocuments[documentID]
	if !ok {
		return nil, errors.New("skill document not found")
	}
	leftLabel := "当前版本"
	rightLabel := "当前版本"
	leftContent := doc.CurrentContent
	rightContent := doc.CurrentContent
	if req.LeftVersionID != "" {
		if version := findVersion(doc.Versions, req.LeftVersionID); version != nil {
			leftLabel = "V" + intString(version.VersionNo)
			leftContent = version.Content
		}
	}
	if req.RightVersionID != "" {
		if version := findVersion(doc.Versions, req.RightVersionID); version != nil {
			rightLabel = "V" + intString(version.VersionNo)
			rightContent = version.Content
		}
	}
	diffText, changed, added, removed := util.BuildUnifiedDiff(leftContent, rightContent)
	return map[string]interface{}{
		"left_label":    leftLabel,
		"right_label":   rightLabel,
		"left_content":  leftContent,
		"right_content": rightContent,
		"diff_text":     diffText,
		"changed_lines": changed,
		"added_lines":   added,
		"removed_lines": removed,
	}, nil
}

func (a *App) RollbackSkillDocument(documentID string, versionID string) (models.SkillDocument, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	doc, ok := a.state.SkillDocuments[documentID]
	if !ok {
		return models.SkillDocument{}, errors.New("skill document not found")
	}
	version := findVersion(doc.Versions, versionID)
	if version == nil {
		return models.SkillDocument{}, errors.New("skill version not found")
	}
	if err := os.MkdirAll(filepath.Join(a.cfg.ManagedSkillsDir, doc.Folder), 0755); err != nil {
		return models.SkillDocument{}, err
	}
	if err := os.WriteFile(filepath.Join(a.cfg.ManagedSkillsDir, doc.Folder, "SKILL.md"), []byte(version.Content), 0644); err != nil {
		return models.SkillDocument{}, err
	}
	for idx := range doc.Versions {
		doc.Versions[idx].IsCurrent = false
	}
	newVersion := models.SkillDocumentVersion{
		ID:              util.NewID("skillver"),
		DocumentID:      doc.ID,
		VersionNo:       len(doc.Versions) + 1,
		ChangeSource:    "rollback",
		ChangeSummary:   "回滚到历史版本",
		Actor:           "admin",
		ContentHash:     util.HashText(version.Content),
		FileSize:        len(version.Content),
		ParentVersionID: version.ID,
		CreatedAt:       util.Now(),
		IsCurrent:       true,
		Content:         version.Content,
	}
	doc.CurrentContent = version.Content
	doc.CurrentHash = newVersion.ContentHash
	doc.CurrentVersionID = newVersion.ID
	doc.VersionCount = len(doc.Versions) + 1
	doc.LastChangeSource = newVersion.ChangeSource
	doc.LastChangeSummary = newVersion.ChangeSummary
	doc.UpdatedAt = newVersion.CreatedAt
	doc.Source = "managed"
	doc.Editable = true
	doc.Versions = append(doc.Versions, newVersion)
	return *doc, a.saveLocked()
}

func (a *App) ListMarketItems() ([]models.SkillMarketItem, error) {
	a.mu.RLock()
	runtimeState := a.state.SkillRuntime
	installs := a.state.SkillInstallations
	a.mu.RUnlock()
	return a.skillSvc.BuildMarketItems(runtimeState, installs)
}

func (a *App) GetMarketItem(itemID string) (models.SkillMarketItem, error) {
	items, err := a.ListMarketItems()
	if err != nil {
		return models.SkillMarketItem{}, err
	}
	for _, item := range items {
		if item.ID == itemID {
			return item, nil
		}
	}
	return models.SkillMarketItem{}, errors.New("skill market item not found")
}

func (a *App) InstallMarketItem(itemID string) (models.SkillMarketItem, error) {
	if _, err := a.skillSvc.InstallBundledSkill(itemID); err != nil {
		return models.SkillMarketItem{}, err
	}
	a.mu.Lock()
	a.state.SkillInstallations[itemID] = &models.SkillInstallation{
		ID:          util.NewID("install"),
		ItemID:      itemID,
		Folder:      itemID,
		Source:      "bundled",
		InstalledAt: util.Now(),
	}
	if a.state.SkillRuntime[itemID] == nil {
		enabled := true
		a.state.SkillRuntime[itemID] = &models.SkillRuntimeConfig{SkillID: itemID, Enabled: &enabled}
	}
	if err := a.saveLocked(); err != nil {
		a.mu.Unlock()
		return models.SkillMarketItem{}, err
	}
	a.mu.Unlock()
	if err := a.SyncSkills(); err != nil {
		return models.SkillMarketItem{}, err
	}
	return a.GetMarketItem(itemID)
}

func (a *App) ImportMarketPackage(fileName string, payload []byte, folder string) (models.SkillMarketItem, error) {
	targetDir, err := a.skillSvc.ImportSkillPackage(fileName, payload, folder)
	if err != nil {
		return models.SkillMarketItem{}, err
	}
	itemID := filepath.Base(targetDir)
	a.mu.Lock()
	a.state.SkillInstallations[itemID] = &models.SkillInstallation{
		ID:          util.NewID("install"),
		ItemID:      itemID,
		Folder:      itemID,
		Source:      "uploaded",
		InstalledAt: util.Now(),
	}
	if a.state.SkillRuntime[itemID] == nil {
		enabled := true
		a.state.SkillRuntime[itemID] = &models.SkillRuntimeConfig{SkillID: itemID, Enabled: &enabled}
	}
	if err := a.saveLocked(); err != nil {
		a.mu.Unlock()
		return models.SkillMarketItem{}, err
	}
	a.mu.Unlock()
	if err := a.SyncSkills(); err != nil {
		return models.SkillMarketItem{}, err
	}
	return a.GetMarketItem(itemID)
}

func (a *App) ListMCPServers() []models.MCPServer {
	a.mu.RLock()
	defer a.mu.RUnlock()
	items := make([]models.MCPServer, 0, len(a.state.MCPServers))
	for _, item := range a.state.MCPServers {
		copyItem := *item
		items = append(items, copyItem)
	}
	sort.Slice(items, func(i, j int) bool { return items[i].UpdatedAt > items[j].UpdatedAt })
	return items
}

func (a *App) CreateMCPServer(server models.MCPServer) (models.MCPServer, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := util.Now()
	server.ID = util.NewID("mcp")
	server.CreatedAt = now
	server.UpdatedAt = now
	server.Versions = []models.MCPServerVersion{{ID: util.NewID("mcpver"), VersionNo: 1, Summary: "create", CreatedAt: now}}
	a.state.MCPServers[server.ID] = &server
	return server, a.saveLocked()
}

func (a *App) UpdateMCPServer(serverID string, patch models.MCPServer) (models.MCPServer, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	current, ok := a.state.MCPServers[serverID]
	if !ok {
		return models.MCPServer{}, errors.New("mcp server not found")
	}
	if patch.Name != "" {
		current.Name = patch.Name
	}
	if patch.ConnectionType != "" {
		current.ConnectionType = patch.ConnectionType
	}
	if patch.ToolPrefix != "" || patch.ToolPrefix == "" {
		current.ToolPrefix = patch.ToolPrefix
	}
	current.Enabled = patch.Enabled
	current.Command = patch.Command
	current.Args = patch.Args
	current.Env = patch.Env
	current.URL = patch.URL
	current.Headers = patch.Headers
	current.UpdatedAt = util.Now()
	current.Versions = append(current.Versions, models.MCPServerVersion{
		ID:        util.NewID("mcpver"),
		VersionNo: len(current.Versions) + 1,
		Summary:   "update",
		CreatedAt: current.UpdatedAt,
	})
	return *current, a.saveLocked()
}

func (a *App) DeleteMCPServer(serverID string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.state.MCPServers, serverID)
	return a.saveLocked()
}

func (a *App) TestMCPServer(serverID string) (models.MCPSmokeResult, error) {
	a.mu.RLock()
	server, ok := a.state.MCPServers[serverID]
	a.mu.RUnlock()
	if !ok {
		return models.MCPSmokeResult{}, errors.New("mcp server not found")
	}
	return probeMCPServer(*server)
}

func (a *App) activeSkillNamesLocked() []string {
	entries := a.activeSkillEntriesLocked()
	names := []string{}
	for _, entry := range entries {
		names = append(names, entry.Name)
	}
	sort.Strings(names)
	return names
}

func (a *App) activeSkillEntriesLocked() []skills.Entry {
	entries, err := a.skillSvc.ScanRuntimeEntries()
	if err != nil {
		return nil
	}
	filtered := make([]skills.Entry, 0, len(entries))
	for _, entry := range entries {
		if state, ok := a.state.SkillRuntime[entry.Folder]; ok && state != nil && state.Enabled != nil && !*state.Enabled {
			continue
		}
		filtered = append(filtered, entry)
	}
	sort.Slice(filtered, func(i, j int) bool { return filtered[i].Folder < filtered[j].Folder })
	return filtered
}

func (a *App) activeMCPServersLocked() []models.MCPServer {
	servers := make([]models.MCPServer, 0, len(a.state.MCPServers))
	for _, server := range a.state.MCPServers {
		if server != nil && server.Enabled {
			servers = append(servers, *server)
		}
	}
	sort.Slice(servers, func(i, j int) bool { return servers[i].Name < servers[j].Name })
	return servers
}

func findVersion(items []models.SkillDocumentVersion, versionID string) *models.SkillDocumentVersion {
	for idx := range items {
		if items[idx].ID == versionID {
			return &items[idx]
		}
	}
	return nil
}

func defaultString(value string, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func intString(value int) string {
	return util.IntString(value)
}

func boolPtr(value bool) *bool {
	return &value
}
