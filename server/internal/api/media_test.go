package api

import (
	"encoding/json"
	"reflect"
	"testing"
)

func TestCreateMediaRoomReqAcceptsStringOrNumericIDs(t *testing.T) {
	var req createMediaRoomReq
	err := json.Unmarshal([]byte(`{
		"kind":"live",
		"conversation_id":9,
		"subject_id":"12",
		"participant_user_ids":[2,"3",null,""]
	}`), &req)
	if err != nil {
		t.Fatalf("Unmarshal() err=%v", err)
	}
	if req.ConversationID.String() != "9" {
		t.Fatalf("conversation_id=%q want 9", req.ConversationID.String())
	}
	if req.SubjectID.String() != "12" {
		t.Fatalf("subject_id=%q want 12", req.SubjectID.String())
	}
	if !reflect.DeepEqual([]string(req.ParticipantUserIDs), []string{"2", "3"}) {
		t.Fatalf("participant_user_ids=%v", req.ParticipantUserIDs)
	}
}
