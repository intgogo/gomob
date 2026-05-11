package signaling

import (
	"encoding/json"
	"testing"
	"time"

	"io.gomob/server/pkg/repo"
)

func TestMessageToRecvPayloadIncludesServerIdentity(t *testing.T) {
	clientMsgID := "client-1"
	createdAt := time.Date(2026, 5, 9, 12, 0, 0, 123, time.UTC)
	msg := &repo.Message{
		ID:             12,
		ConversationID: 34,
		ServerSeq:      5,
		Kind:           "text",
		Payload:        json.RawMessage(`{"text":"hello"}`),
		ClientMsgID:    &clientMsgID,
		CreatedAt:      createdAt,
	}

	payload := messageToRecvPayload(msg, 7)

	if payload.MessageID != msg.ID ||
		payload.ConversationID != msg.ConversationID ||
		payload.ServerSeq != msg.ServerSeq ||
		payload.SenderID != 7 ||
		payload.Kind != msg.Kind ||
		payload.ClientMsgID != clientMsgID ||
		payload.CreatedAt != createdAt.Format(time.RFC3339Nano) {
		t.Fatalf("msg.recv payload 不完整: %+v", payload)
	}
	if string(payload.Content) != string(msg.Payload) {
		t.Fatalf("content=%s want %s", payload.Content, msg.Payload)
	}
}
