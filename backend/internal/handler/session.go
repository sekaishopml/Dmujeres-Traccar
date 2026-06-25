package handler

import (
	"context"
	"log"
	"time"

	"dmujeres-traccar/internal/auth"
	"dmujeres-traccar/internal/model"
	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
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

	var user model.User
	query := "SELECT id, name, email, administrator FROM tc_users WHERE id = $1"
	err = h.DB.QueryRow(context.Background(), query, userID).Scan(&user.ID, &user.Name, &user.Email, &user.Admin)
	if err != nil {
		return c.Status(fiber.StatusNotFound).SendString("User not found")
	}

	return c.JSON(user)
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

	var user model.User
	var passwordHash string
	query := "SELECT id, name, email, hashedpassword, administrator FROM tc_users WHERE email = $1"
	err := h.DB.QueryRow(context.Background(), query, email).Scan(&user.ID, &user.Name, &user.Email, &passwordHash, &user.Admin)
	if err != nil {
		// If db is empty, allow auto-create of first user as admin for easy setup
		var count int
		h.DB.QueryRow(context.Background(), "SELECT COUNT(*) FROM tc_users").Scan(&count)
		if count == 0 {
			log.Println("No users exist. Creating first admin user:", email)
			err = h.DB.QueryRow(context.Background(),
				"INSERT INTO tc_users (name, email, hashedpassword, administrator) VALUES ($1, $1, $2, true) RETURNING id",
				email, password).Scan(&user.ID)
			if err == nil {
				user.Name = email
				user.Email = email
				user.Admin = true
				cookie := auth.CreateSessionCookie(user.ID)
				c.Cookie(cookie)
				return c.JSON(user)
			}
		}
		return c.Status(fiber.StatusUnauthorized).SendString("Invalid email or password")
	}

	// Simple password check (replace with bcrypt in production)
	if password != passwordHash {
		return c.Status(fiber.StatusUnauthorized).SendString("Invalid email or password")
	}

	cookie := auth.CreateSessionCookie(user.ID)
	c.Cookie(cookie)

	return c.JSON(user)
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
