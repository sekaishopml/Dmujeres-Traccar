package handler

import (
        "context"
        "encoding/json"
        "strconv"

        "dmujeres-traccar/internal/auth"
        "dmujeres-traccar/internal/model"
        "github.com/gofiber/fiber/v2"
        "github.com/jackc/pgx/v5"
        "github.com/jackc/pgx/v5/pgxpool"
        "golang.org/x/crypto/bcrypt"
)

type UserHandler struct {
        DB *pgxpool.Pool
}

func NewUserHandler(db *pgxpool.Pool) *UserHandler {
        return &UserHandler{DB: db}
}

func scanUser(row pgx.Row) (*model.User, error) {
        var u model.User
        var attrsStr *string
        err := row.Scan(
                &u.ID, &u.Name, &u.Email, &u.PasswordHash, &u.Admin,
                &u.Readonly, &u.Map, &u.Latitude, &u.Longitude, &u.Zoom,
                &u.TwelveHourFormat, &attrsStr, &u.CoordinateFormat, &u.Disabled, &u.Phone,
        )
        if err != nil {
                return nil, err
        }
        
        u.Attributes = make(map[string]interface{})
        if attrsStr != nil && *attrsStr != "" {
                _ = json.Unmarshal([]byte(*attrsStr), &u.Attributes)
        }
        return &u, nil
}

func scanUsers(rows pgx.Rows) ([]model.User, error) {
        defer rows.Close()
        var users []model.User
        for rows.Next() {
                var u model.User
                var attrsStr *string
                err := rows.Scan(
                        &u.ID, &u.Name, &u.Email, &u.PasswordHash, &u.Admin,
                        &u.Readonly, &u.Map, &u.Latitude, &u.Longitude, &u.Zoom,
                        &u.TwelveHourFormat, &attrsStr, &u.CoordinateFormat, &u.Disabled, &u.Phone,
                )
                if err == nil {
                        u.Attributes = make(map[string]interface{})
                        if attrsStr != nil && *attrsStr != "" {
                                _ = json.Unmarshal([]byte(*attrsStr), &u.Attributes)
                        }
                        users = append(users, u)
                }
        }
        return users, nil
}

func (h *UserHandler) GetUsers(c *fiber.Ctx) error {
        userID, err := auth.GetUserFromCookie(c)
        if err != nil {
                return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
        }

        var isAdmin bool
        h.DB.QueryRow(context.Background(), "SELECT administrator FROM tc_users WHERE id = $1", userID).Scan(&isAdmin)

        var query string
        var args []interface{}
        if isAdmin {
                query = `SELECT id, name, email, COALESCE(hashedpassword, ''), administrator, COALESCE(readonly, false), COALESCE(map, ''), COALESCE(latitude, 0.0), COALESCE(longitude, 0.0), COALESCE(zoom, 0), COALESCE(twelvehourformat, false), attributes, COALESCE(coordinateformat, ''), COALESCE(disabled, false), COALESCE(phone, '') FROM tc_users ORDER BY id ASC`
        } else {
                query = `SELECT id, name, email, COALESCE(hashedpassword, ''), administrator, COALESCE(readonly, false), COALESCE(map, ''), COALESCE(latitude, 0.0), COALESCE(longitude, 0.0), COALESCE(zoom, 0), COALESCE(twelvehourformat, false), attributes, COALESCE(coordinateformat, ''), COALESCE(disabled, false), COALESCE(phone, '') FROM tc_users WHERE id = $1`
                args = append(args, userID)
        }

        rows, err := h.DB.Query(context.Background(), query, args...)
        if err != nil {
                return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
        }
        
        users, err := scanUsers(rows)
        if err != nil {
                return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
        }
        return c.JSON(users)
}

func (h *UserHandler) CreateUser(c *fiber.Ctx) error {
        var isAdmin bool
        userID, err := auth.GetUserFromCookie(c)
        if err == nil {
                h.DB.QueryRow(context.Background(), "SELECT administrator FROM tc_users WHERE id = $1", userID).Scan(&isAdmin)
        }

        var count int
        h.DB.QueryRow(context.Background(), "SELECT COUNT(*) FROM tc_users").Scan(&count)

        if count > 0 && !isAdmin {
                return c.Status(fiber.StatusForbidden).SendString("Only administrators can create users")
        }

        var u model.User
        if err := c.BodyParser(&u); err != nil {
                return c.Status(fiber.StatusBadRequest).SendString(err.Error())
        }

        if u.Email == "" || u.Password == "" {
                return c.Status(fiber.StatusBadRequest).SendString("Email and password are required")
        }

        hashBytes, err := bcrypt.GenerateFromPassword([]byte(u.Password), bcrypt.DefaultCost)
        if err != nil {
                return c.Status(fiber.StatusInternalServerError).SendString("Password hashing failed")
        }
        u.PasswordHash = string(hashBytes)

        attributesBytes, _ := json.Marshal(u.Attributes)
        if len(u.Attributes) == 0 {
                attributesBytes = []byte("{}")
        }

        query := `INSERT INTO tc_users (name, email, hashedpassword, administrator, readonly, map, latitude, longitude, zoom, twelvehourformat, attributes, coordinateformat, disabled, phone)
                  VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
                  RETURNING id`
        
        err = h.DB.QueryRow(context.Background(), query,
                u.Name, u.Email, u.PasswordHash, u.Admin, u.Readonly, u.Map, u.Latitude, u.Longitude, u.Zoom, u.TwelveHourFormat, string(attributesBytes), u.CoordinateFormat, u.Disabled, u.Phone,
        ).Scan(&u.ID)
        
        if err != nil {
                return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
        }

        return c.JSON(u)
}

func (h *UserHandler) UpdateUser(c *fiber.Ctx) error {
        currentUserID, err := auth.GetUserFromCookie(c)
        if err != nil {
                return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
        }

        idStr := c.Params("id")
        targetUserID, _ := strconv.ParseInt(idStr, 10, 64)

        var currentIsAdmin bool
        h.DB.QueryRow(context.Background(), "SELECT administrator FROM tc_users WHERE id = $1", currentUserID).Scan(&currentIsAdmin)

        if currentUserID != targetUserID && !currentIsAdmin {
                return c.Status(fiber.StatusForbidden).SendString("Forbidden")
        }

        var u model.User
        if err := c.BodyParser(&u); err != nil {
                return c.Status(fiber.StatusBadRequest).SendString(err.Error())
        }

        attributesBytes, _ := json.Marshal(u.Attributes)
        if len(u.Attributes) == 0 {
                attributesBytes = []byte("{}")
        }

        var updatePasswordQuery string
        var args []interface{}
        
        if u.Password != "" {
                hashBytes, err := bcrypt.GenerateFromPassword([]byte(u.Password), bcrypt.DefaultCost)
                if err != nil {
                        return c.Status(fiber.StatusInternalServerError).SendString("Password hashing failed")
                }
                u.PasswordHash = string(hashBytes)
                updatePasswordQuery = ", hashedpassword = $14"
        }

        query := `UPDATE tc_users SET
                      name = $1, email = $2, administrator = $3, readonly = $4, map = $5,
                      latitude = $6, longitude = $7, zoom = $8, twelvehourformat = $9,
                      attributes = $10, coordinateformat = $11, disabled = $12, phone = $13` + updatePasswordQuery + `
                  WHERE id = ` + strconv.FormatInt(targetUserID, 10)
        
        args = append(args, u.Name, u.Email, u.Admin, u.Readonly, u.Map, u.Latitude, u.Longitude, u.Zoom, u.TwelveHourFormat, string(attributesBytes), u.CoordinateFormat, u.Disabled, u.Phone)
        if u.Password != "" {
                args = append(args, u.PasswordHash)
        }

        _, err = h.DB.Exec(context.Background(), query, args...)
        if err != nil {
                return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
        }

        u.ID = targetUserID
        return c.JSON(u)
}

func (h *UserHandler) DeleteUser(c *fiber.Ctx) error {
        currentUserID, err := auth.GetUserFromCookie(c)
        if err != nil {
                return c.Status(fiber.StatusUnauthorized).SendString("Unauthorized")
        }

        var currentIsAdmin bool
        h.DB.QueryRow(context.Background(), "SELECT administrator FROM tc_users WHERE id = $1", currentUserID).Scan(&currentIsAdmin)

        if !currentIsAdmin {
                return c.Status(fiber.StatusForbidden).SendString("Only administrators can delete users")
        }

        idStr := c.Params("id")
        targetUserID, _ := strconv.ParseInt(idStr, 10, 64)

        if currentUserID == targetUserID {
                return c.Status(fiber.StatusBadRequest).SendString("Cannot delete yourself")
        }

        _, err = h.DB.Exec(context.Background(), "DELETE FROM tc_users WHERE id = $1", targetUserID)
        if err != nil {
                return c.Status(fiber.StatusInternalServerError).SendString(err.Error())
        }

        return c.SendString("OK")
}
