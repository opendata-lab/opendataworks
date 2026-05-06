package runtime

import (
	"context"
	"errors"
	"strings"
	"testing"

	sdkapi "github.com/stellarlinkco/agentsdk-go/pkg/api"
	sdkmodel "github.com/stellarlinkco/agentsdk-go/pkg/model"

	"opendataagent/server/internal/agent"
	"opendataagent/server/internal/models"
)

func intPtr(value int) *int {
	return &value
}

type failingStreamModel struct {
	err error
}

func (m failingStreamModel) Complete(context.Context, sdkmodel.Request) (*sdkmodel.Response, error) {
	return nil, m.err
}

func (m failingStreamModel) CompleteStream(context.Context, sdkmodel.Request, sdkmodel.StreamHandler) error {
	return m.err
}

type emptyStreamModel struct{}

func (m emptyStreamModel) Complete(context.Context, sdkmodel.Request) (*sdkmodel.Response, error) {
	return &sdkmodel.Response{Message: sdkmodel.Message{Role: "assistant"}}, nil
}

func (m emptyStreamModel) CompleteStream(_ context.Context, _ sdkmodel.Request, cb sdkmodel.StreamHandler) error {
	return cb(sdkmodel.StreamResult{
		Final: true,
		Response: &sdkmodel.Response{
			Message: sdkmodel.Message{Role: "assistant"},
		},
	})
}

func TestRunReturnsErrorWhenStreamReportsModelError(t *testing.T) {
	t.Setenv("ODA_DISABLE_AGENT_TOOLS", "1")

	result, err := Run(context.Background(), RunInput{
		Prompt:      "hello",
		SessionID:   "stream-error",
		ProjectRoot: t.TempDir(),
		ModelFactory: sdkapi.ModelFactoryFunc(func(context.Context) (sdkmodel.Model, error) {
			return failingStreamModel{err: errors.New("gateway stream failed")}, nil
		}),
	}, nil)

	if err == nil || !strings.Contains(err.Error(), "gateway stream failed") {
		t.Fatalf("expected stream error, got result=%#v err=%v", result, err)
	}
}

func TestRunReturnsErrorWhenModelProducesNoContent(t *testing.T) {
	t.Setenv("ODA_DISABLE_AGENT_TOOLS", "1")

	result, err := Run(context.Background(), RunInput{
		Prompt:      "hello",
		SessionID:   "empty-response",
		ProjectRoot: t.TempDir(),
		ModelFactory: sdkapi.ModelFactoryFunc(func(context.Context) (sdkmodel.Model, error) {
			return emptyStreamModel{}, nil
		}),
	}, nil)

	if err == nil || !strings.Contains(err.Error(), "model returned empty response") {
		t.Fatalf("expected empty response error, got result=%#v err=%v", result, err)
	}
}

func TestCollectorPreservesMarkdownLineBreaksInTextDeltas(t *testing.T) {
	collector := newCollector()

	collector.Apply(models.TaskEvent{Type: sdkapi.EventMessageStart})
	collector.Apply(models.TaskEvent{
		Type: sdkapi.EventContentBlockStart,
		ContentBlock: map[string]interface{}{
			"type": "text",
		},
		Index: intPtr(0),
	})

	for _, chunk := range []string{
		"### 标题",
		"\n",
		"\n",
		"| 名称 | 数值 |",
		"\n",
		"| --- | --- |",
		"\n",
		"| A | 10 |",
	} {
		collector.Apply(models.TaskEvent{
			Type: sdkapi.EventContentBlockDelta,
			Delta: map[string]interface{}{
				"type": "text_delta",
				"text": chunk,
			},
			Index: intPtr(0),
		})
	}

	result := collector.Result()
	if len(result.Blocks) != 1 {
		t.Fatalf("expected 1 block, got %d", len(result.Blocks))
	}

	want := "### 标题\n\n| 名称 | 数值 |\n| --- | --- |\n| A | 10 |"
	if got := result.Blocks[0].Text; got != want {
		t.Fatalf("unexpected block text: got %q want %q", got, want)
	}
	if got := result.Final; got != want {
		t.Fatalf("unexpected final text: got %q want %q", got, want)
	}
}

func TestCollectorSplitsReasoningEnvelopeIntoThinkingBlock(t *testing.T) {
	collector := newCollector()

	collector.Apply(models.TaskEvent{Type: sdkapi.EventMessageStart})
	collector.Apply(models.TaskEvent{
		Type: sdkapi.EventContentBlockStart,
		ContentBlock: map[string]interface{}{
			"type": "text",
		},
		Index: intPtr(0),
	})

	stream := agent.EncodeReasoningEnvelope("call runtime_context first", "smoke-ok")
	for _, chunk := range []string{
		stream[:12],
		stream[12:29],
		stream[29:41],
		stream[41:],
	} {
		collector.Apply(models.TaskEvent{
			Type: sdkapi.EventContentBlockDelta,
			Delta: map[string]interface{}{
				"type": "text_delta",
				"text": chunk,
			},
			Index: intPtr(0),
		})
	}
	collector.Apply(models.TaskEvent{Type: sdkapi.EventContentBlockStop, Index: intPtr(0)})

	result := collector.Result()
	if len(result.Blocks) != 2 {
		t.Fatalf("expected 2 blocks, got %d", len(result.Blocks))
	}
	if result.Blocks[0].Type != "thinking" {
		t.Fatalf("expected first block thinking, got %q", result.Blocks[0].Type)
	}
	if result.Blocks[0].Text != "call runtime_context first" {
		t.Fatalf("unexpected thinking text %q", result.Blocks[0].Text)
	}
	if result.Blocks[1].Type != "main_text" {
		t.Fatalf("expected second block main_text, got %q", result.Blocks[1].Type)
	}
	if result.Blocks[1].Text != "smoke-ok" {
		t.Fatalf("unexpected main text %q", result.Blocks[1].Text)
	}
	if result.Final != "smoke-ok" {
		t.Fatalf("unexpected final text %q", result.Final)
	}
}

func TestCollectorTrimsLeadingBlankLinesAfterReasoningAcrossDeltaBoundaries(t *testing.T) {
	collector := newCollector()

	collector.Apply(models.TaskEvent{Type: sdkapi.EventMessageStart})
	collector.Apply(models.TaskEvent{
		Type: sdkapi.EventContentBlockStart,
		ContentBlock: map[string]interface{}{
			"type": "text",
		},
		Index: intPtr(0),
	})

	collector.Apply(models.TaskEvent{
		Type: sdkapi.EventContentBlockDelta,
		Delta: map[string]interface{}{
			"type": "text_delta",
			"text": agent.ReasoningEnvelopeStart + "step by step" + agent.ReasoningEnvelopeEnd,
		},
		Index: intPtr(0),
	})
	collector.Apply(models.TaskEvent{
		Type: sdkapi.EventContentBlockDelta,
		Delta: map[string]interface{}{
			"type": "text_delta",
			"text": "\n\nsmoke-ok",
		},
		Index: intPtr(0),
	})
	collector.Apply(models.TaskEvent{Type: sdkapi.EventContentBlockStop, Index: intPtr(0)})

	result := collector.Result()
	if len(result.Blocks) != 2 {
		t.Fatalf("expected 2 blocks, got %d", len(result.Blocks))
	}
	if result.Blocks[1].Text != "smoke-ok" {
		t.Fatalf("unexpected main text %q", result.Blocks[1].Text)
	}
}

func TestCollectorResultNormalizesReasoningEnvelopeFromUnsplittedMainText(t *testing.T) {
	collector := newCollector()
	collector.appendMainText("m1-cb-0", agent.EncodeReasoningEnvelope("先确认上下文", "smoke-ok"))
	collector.completeTextBlocks("m1-cb-0")

	result := collector.Result()
	if len(result.Blocks) != 2 {
		t.Fatalf("expected 2 blocks, got %d", len(result.Blocks))
	}
	if result.Blocks[0].Type != "thinking" || result.Blocks[0].Text != "先确认上下文" {
		t.Fatalf("unexpected thinking block %#v", result.Blocks[0])
	}
	if result.Blocks[1].Type != "main_text" || result.Blocks[1].Text != "smoke-ok" {
		t.Fatalf("unexpected main block %#v", result.Blocks[1])
	}
	if result.Final != "smoke-ok" {
		t.Fatalf("unexpected final text %q", result.Final)
	}
}
