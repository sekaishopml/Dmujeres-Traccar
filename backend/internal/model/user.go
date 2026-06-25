package model

type User struct {
        ID               int64                  `json:"id" db:"id"`
        Name             string                 `json:"name" db:"name"`
        Email            string                 `json:"email" db:"email"`
        Password         string                 `json:"password,omitempty" db:"-"`
        PasswordHash     string                 `json:"-" db:"hashedpassword"`
        Admin            bool                   `json:"administrator" db:"administrator"`
        Readonly         bool                   `json:"readOnly" db:"readonly"`
        Map              string                 `json:"map" db:"map"`
        Latitude         float64                `json:"latitude" db:"latitude"`
        Longitude        float64                `json:"longitude" db:"longitude"`
        Zoom             int                    `json:"zoom" db:"zoom"`
        TwelveHourFormat bool                   `json:"twelveHourFormat" db:"twelvehourformat"`
        Attributes       map[string]interface{} `json:"attributes" db:"-"`
        CoordinateFormat string                 `json:"coordinateFormat" db:"coordinateformat"`
        Disabled         bool                   `json:"disabled" db:"disabled"`
        Phone            string                 `json:"phone" db:"phone"`
}
