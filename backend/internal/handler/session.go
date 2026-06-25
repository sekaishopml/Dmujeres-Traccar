package handler

import (
        "context"
        "encoding/json"
        "log"
        "time"

        "dmujeres-traccar/internal/auth"
        "dmujeres-traccar/internal/model"
        "github.com/gofiber/fiber/v2"
        "github.com/jackc/pgx/v5/pgxpool"
        "golang.org/x/crypto/bcrypt"
)

type SessionHandler struct {
        DB *pgxpool.Pool
}

func NewSessionHandler(db *pgxpool.Pool) *SessionHandler {
        return &SessionHandler{DB: db}
}

func (h *SessionHandler) GetSession(c *fiber.Ctx) error {
        userID, err := auth.GetUserFromCookie(c)
        if err != nil {
                return c.Status(fiber.StatusNotFound).SendString("Session not found")
        }

        var u model.User
        var attrsStr *string
        query := `SELECT id, name, email, COALESCE(hashedpassword, ''), administrator, COALESCE(readonly, false), COALESCE(map, ''), COALESCE(latitude, 0.0), COALESCE(longitude, 0.0), COALESCE(zoom, 0), COALESCE(twelvehourformat, false), attributes, COALESCE(coordinateformat, ''), COALESCE(disabled, false), COALESCE(phone, '') FROM tc_users WHERE id = $1`
        
        err = h.DB.QueryRow(context.Background(), query, userID).Scan(
                &u.ID, &u.Name, &u.Email, &u.PasswordHash, &u.Admin,
                &u.Readonly, &u.Map, &u.Latitude, &u.Longitude, &u.Zoom,
                &u.TwelveHourFormat, &attrsStr, &u.CoordinateFormat, &u.Disabled, &u.Phone,
        )
        if err != nil {
                return c.Status(fiber.StatusNotFound).SendString("User not found")
        }

        u.Attributes = make(map[string]interface{})
        if attrsStr != nil && *attrsStr != "" {
                _ = json.Unmarshal([]byte(*attrsStr), &u.Attributes)
        }

        return c.JSON(u)
}

func (h *SessionHandler) Login(c *fiber.Ctx) error {
        email := c.FormValue("email")
        password := c.FormValue("password")

        // Support JSON body fallback
        if email == "" {
                type LoginReq struct {
                        Email    string `json:"email"`
                        Password string `json:"password"`
                }
                var req LoginReq
                if err := c.BodyParser(&req); err == nil {
                        email = req.Email
                        password = req.Password
                }
        }

        if email == "" || password == "" {
                return c.Status(fiber.StatusBadRequest).SendString("Missing email or password")
        }

        var u model.User
        var attrsStr *string
        query := `SELECT id, name, email, COALESCE(hashedpassword, ''), administrator, COALESCE(readonly, false), COALESCE(map, ''), COALESCE(latitude, 0.0), COALESCE(longitude, 0.0), COALESCE(zoom, 0), COALESCE(twelvehourformat, false), attributes, COALESCE(coordinateformat, ''), COALESCE(disabled, false), COALESCE(phone, '') FROM tc_users WHERE email = $1`
        
        err := h.DB.QueryRow(context.Background(), query, email).Scan(
                &u.ID, &u.Name, &u.Email, &u.PasswordHash, &u.Admin,
                &u.Readonly, &u.Map, &u.Latitude, &u.Longitude, &u.Zoom,
                &u.TwelveHourFormat, &attrsStr, &u.CoordinateFormat, &u.Disabled, &u.Phone,
        )
        if err != nil {
                // If db is empty, allow auto-create of first user as admin for easy setup
                var count int
                h.DB.QueryRow(context.Background(), "SELECT COUNT(*) FROM tc_users").Scan(&count)
                if count == 0 {
                        log.Println("No users exist. Creating first admin user:", email)
                        hashBytes, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
                        if err != nil {
                                return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
                        }
                        
                        var newID int64
                        err = h.DB.QueryRow(context.Background(),
                                "INSERT INTO tc_users (name, email, hashedpassword, administrator) VALUES ($1, $1, $2, true) RETURNING id",
                                email, string(hashBytes)).Scan(&newID)
                        if err == nil {
                                u.ID = newID
                                u.Name = email
                                u.Email = email
                                u.Admin = true
                                u.Attributes = make(map[string]interface{})
                                cookie := auth.CreateSessionCookie(u.ID)
                                c.Cookie(cookie)
                                return c.JSON(u)
                        }
                }
                return c.Status(fiber.StatusUnauthorized).SendString("Invalid email or password")
        }

        // Compare password using bcrypt
        err = bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password))
        if err != nil {
                return c.Status(fiber.StatusUnauthorized).SendString("Invalid email or password")
        }

        u.Attributes = make(map[string]interface{})
        if attrsStr != nil && *attrsStr != "" {
                _ = json.Unmarshal([]byte(*attrsStr), &u.Attributes)
        }

        cookie := auth.CreateSessionCookie(u.ID)
        c.Cookie(cookie)

        return c.JSON(u)
}

func (h *SessionHandler) Logout(c *fiber.Ctx) error {
        cookie := &fiber.Cookie{
                Name:     "JSESSIONID",
                Value:    "",
                Expires:  time.Now().Add(-24 * time.Hour),
                HTTPOnly: true,
                Path:     "/",
        }
        c.Cookie(cookie)
        return c.SendString("OK")
}
