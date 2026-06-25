package model

import "time"

type Position struct {
	ID         int64     `json:"id" db:"id"`
	DeviceID   int64     `json:"deviceId" db:"deviceid"`
	Protocol   string    `json:"protocol" db:"protocol"`
	ServerTime time.Time `json:"serverTime" db:"servertime"`
	DeviceTime time.Time `json:"deviceTime" db:"devicetime"`
	Latitude   float64   `json:"latitude" db:"latitude"`
	Longitude  float64   `json:"longitude" db:"longitude"`
	Speed      float64   `json:"speed" db:"speed"`
	Course     float64   `json:"course" db:"course"`
	Altitude   float64   `json:"altitude" db:"altitude"`
	Attributes string    `json:"attributes" db:"attributes"`
}
