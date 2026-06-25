package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
)

var secretKey = []byte("dmujeres-secret-key-123456789")

func CreateSessionCookie(userID int64) *fiber.Cookie {
	val := strconv.FormatInt(userID, 10)
	mac := hmac.New(sha256.New, secretKey)
	mac.Write([]byte(val))
	signature := base64.StdEncoding.EncodeToString(mac.Sum(nil))
	cookieVal := fmt.Sprintf("%s:%s", val, signature)

	return &fiber.Cookie{
		Name:     "JSESSIONID",
		Value:    cookieVal,
		Expires:  time.Now().Add(24 * time.Hour),
		HTTPOnly: true,
		Path:     "/",
	}
}

func GetUserFromCookie(c *fiber.Ctx) (int64, error) {
	cookieVal := c.Cookies("JSESSIONID")
	if cookieVal == "" {
		return 0, fmt.Errorf("no session cookie")
	}

	parts := strings.Split(cookieVal, ":")
	if len(parts) != 2 {
		return 0, fmt.Errorf("invalid cookie format")
	}

	userIDStr, signature := parts[0], parts[1]
	mac := hmac.New(sha256.New, secretKey)
	mac.Write([]byte(userIDStr))
	expectedSignature := base64.StdEncoding.EncodeToString(mac.Sum(nil))

	if signature != expectedSignature {
		return 0, fmt.Errorf("invalid signature")
	}

	userID, err := strconv.ParseInt(userIDStr, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid user id")
	}

	return userID, nil
}
