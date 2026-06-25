package model

import "time"

type Device struct {
ID         int64     `json:"id" db:"id"`
Name       string    `json:"name" db:"name"`
UniqueID   string    `json:"uniqueId" db:"uniqueid"`
Status     string    `json:"status" db:"status"`
LastUpdate time.Time `json:"lastUpdate" db:"lastupdate"`
PositionID int64     `json:"positionId" db:"positionid"`
Attributes string    `json:"attributes" db:"attributes"`
}

