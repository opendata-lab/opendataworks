package app

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/modelcontextprotocol/go-sdk/mcp"

	"opendataagent/server/internal/models"
)

func probeMCPServer(server models.MCPServer) (models.MCPSmokeResult, error) {
	switch strings.ToLower(strings.TrimSpace(server.ConnectionType)) {
	case "", "process", "stdio":
		return probeProcessMCP(server)
	default:
		return probeRemoteMCP(server)
	}
}

func probeProcessMCP(server models.MCPServer) (models.MCPSmokeResult, error) {
	command := strings.TrimSpace(server.Command)
	if command == "" {
		return models.MCPSmokeResult{OK: false, Message: "MCP process command 不能为空"}, errors.New("mcp process command is required")
	}
	if _, err := exec.LookPath(command); err != nil {
		return models.MCPSmokeResult{OK: false, Message: "命令不可执行: " + err.Error()}, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()

	cmd := exec.CommandContext(ctx, command, server.Args...)
	cmd.Env = append(os.Environ(), flattenProcessEnv(server.Env)...)
	client := mcp.NewClient(&mcp.Implementation{
		Name:    "opendataagent-mcp-probe",
		Version: "1.2.0",
	}, nil)
	session, err := client.Connect(ctx, &mcp.CommandTransport{
		Command:           cmd,
		TerminateDuration: time.Second,
	}, nil)
	if err != nil {
		return models.MCPSmokeResult{OK: false, Message: "MCP 初始化失败: " + err.Error()}, err
	}
	defer session.Close()

	tools, err := session.ListTools(ctx, &mcp.ListToolsParams{})
	if err != nil {
		return models.MCPSmokeResult{OK: false, Message: "MCP tools/list 失败: " + err.Error()}, err
	}
	return models.MCPSmokeResult{OK: true, Message: fmt.Sprintf("MCP 初始化成功，可见 %d 个工具", len(tools.Tools))}, nil
}

func probeRemoteMCP(server models.MCPServer) (models.MCPSmokeResult, error) {
	url := strings.TrimSpace(server.URL)
	if url == "" {
		return models.MCPSmokeResult{OK: false, Message: "远程 MCP URL 不能为空"}, errors.New("mcp remote url is required")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	client := mcp.NewClient(&mcp.Implementation{
		Name:    "opendataagent-mcp-probe",
		Version: "1.2.0",
	}, nil)
	httpClient := &http.Client{
		Timeout: 5 * time.Second,
		Transport: &headerRoundTripper{
			base:    http.DefaultTransport,
			headers: server.Headers,
		},
	}

	mode, endpoint := hintedRemoteEndpoint(url)
	if endpoint == "" {
		endpoint = url
	}

	var session *mcp.ClientSession
	var err error
	switch mode {
	case "sse":
		session, err = client.Connect(ctx, &mcp.SSEClientTransport{
			Endpoint:   endpoint,
			HTTPClient: httpClient,
		}, nil)
	case "stream":
		session, err = client.Connect(ctx, &mcp.StreamableClientTransport{
			Endpoint:   endpoint,
			HTTPClient: httpClient,
			MaxRetries: -1,
		}, nil)
	default:
		session, err = client.Connect(ctx, &mcp.StreamableClientTransport{
			Endpoint:   endpoint,
			HTTPClient: httpClient,
			MaxRetries: -1,
		}, nil)
		if err != nil {
			session, err = client.Connect(ctx, &mcp.SSEClientTransport{
				Endpoint:   endpoint,
				HTTPClient: httpClient,
			}, nil)
		}
	}
	if err != nil {
		return models.MCPSmokeResult{OK: false, Message: "远程 MCP 初始化失败: " + err.Error()}, err
	}
	defer session.Close()

	tools, err := session.ListTools(ctx, &mcp.ListToolsParams{})
	if err != nil {
		return models.MCPSmokeResult{OK: false, Message: "远程 MCP tools/list 失败: " + err.Error()}, err
	}
	return models.MCPSmokeResult{OK: true, Message: fmt.Sprintf("远程 MCP 初始化成功，可见 %d 个工具", len(tools.Tools))}, nil
}

func hintedRemoteEndpoint(raw string) (mode string, endpoint string) {
	raw = strings.TrimSpace(raw)
	parsed, err := url.Parse(raw)
	if err != nil || parsed == nil {
		return "", raw
	}
	scheme := strings.ToLower(parsed.Scheme)
	base, hint, ok := strings.Cut(scheme, "+")
	if !ok {
		return "", raw
	}
	if base != "http" && base != "https" {
		return "", raw
	}
	parsed.Scheme = base
	switch hint {
	case "sse":
		return "sse", parsed.String()
	case "stream", "streamable", "http", "json":
		return "stream", parsed.String()
	default:
		return "", raw
	}
}

func flattenProcessEnv(env map[string]string) []string {
	if len(env) == 0 {
		return nil
	}
	items := make([]string, 0, len(env))
	for key, value := range env {
		key = strings.TrimSpace(key)
		if key == "" {
			continue
		}
		items = append(items, key+"="+value)
	}
	return items
}

type headerRoundTripper struct {
	base    http.RoundTripper
	headers map[string]string
}

func (rt *headerRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	clone := req.Clone(req.Context())
	for key, value := range rt.headers {
		clone.Header.Set(key, value)
	}
	base := rt.base
	if base == nil {
		base = http.DefaultTransport
	}
	return base.RoundTrip(clone)
}
