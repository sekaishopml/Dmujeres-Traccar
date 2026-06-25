package model

import "time"

type User struct {
ID           int64     `json:"id" db:"id"`
Name         string    `json:"name" db:"name"`
Email        string    `json:"email" db:"email"`
PasswordHash string    `json:"-" db:"hashedpassword"`
Admin        bool      `json:"administrator" db:"administrator"`
Created      time.Time `json:"created" db:"created"`
}

