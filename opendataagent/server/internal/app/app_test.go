package app

import (
	"context"
	"errors"
	"testing"
	"time"

	"opendataagent/server/internal/models"
	"opendataagent/server/internal/runtime"
)

type memoryStore struct {
	state models.StateSnapshot
}

func (s *memoryStore) Load() (models.StateSnapshot, error) {
	return s.state, nil
}

func (s *memoryStore) Save(state models.StateSnapshot) error {
	s.state = state
	return nil
}

func (s *memoryStore) Close() error {
	return nil
}

func TestFinishTaskErrorBroadcastsRuntimeErrorEvent(t *testing.T) {
	now := time.Now().UTC().Format(time.RFC3339)
	taskID := "task-1"
	topicID := "topic-1"
	messageID := "assistant-1"
	core := &App{
		store:       &memoryStore{},
		subscribers: map[string]map[chan models.TaskEvent]struct{}{},
		taskCancels: map[string]context.CancelFunc{},
		state: models.StateSnapshot{
			Topics: map[string]*models.Topic{
				topicID: {
					TopicID:           topicID,
					CurrentTaskID:     taskID,
					CurrentTaskStatus: "running",
					Messages: []models.Message{
						{
							MessageID:  messageID,
							TopicID:    topicID,
							SenderType: "assistant",
							Status:     "running",
							TaskID:     taskID,
							CreatedAt:  now,
							UpdatedAt:  now,
						},
					},
				},
			},
			Tasks: map[string]*models.Task{
				taskID: {
					TaskID:             taskID,
					TopicID:            topicID,
					AssistantMessageID: messageID,
					TaskStatus:         "running",
					CreatedAt:          now,
					UpdatedAt:          now,
				},
			},
			TaskEvents: map[string][]models.TaskEvent{},
		},
	}

	events, unsubscribe := core.Subscribe(taskID)
	defer unsubscribe()

	core.finishTaskError(taskID, errors.New("gateway stream failed"), runtime.TaskResult{})

	select {
	case event := <-events:
		if event.Type != "error" {
			t.Fatalf("expected error event, got %#v", event)
		}
		if got := event.Payload["message"]; got != "gateway stream failed" {
			t.Fatalf("unexpected error message payload: %#v", event.Payload)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for error event")
	}
}
