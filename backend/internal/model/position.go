package model

import (
	"encoding/json"
	"time"
)

type Position struct {
	ID         int64           `json:"id"`
	DeviceID   int64           `json:"deviceId"`
	Protocol   string          `json:"protocol"`
	ServerTime time.Time       `json:"serverTime"`
	DeviceTime time.Time       `json:"deviceTime"`
	FixTime    time.Time       `json:"fixTime"`
	Valid      bool            `json:"valid"`
	Latitude   float64         `json:"latitude"`
	Longitude  float64         `json:"longitude"`
	Speed      float64         `json:"speed"`
	Course     float64         `json:"course"`
	Altitude   float64         `json:"altitude"`
	Address    string          `json:"address"`
	Accuracy   float64         `json:"accuracy"`
	Network    string          `json:"network"` // Keep as string (can be json string if needed)
	Attributes json.RawMessage `json:"attributes"`
}
