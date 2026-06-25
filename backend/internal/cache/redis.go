package cache

import (
	"context"
	"log"

	"github.com/redis/go-redis/v9"
)

type RedisClient struct {
	Client *redis.Client
}

func ConnectRedis(addr string) (*RedisClient, error) {
	rdb := redis.NewClient(&redis.Options{
		Addr: addr,
	})

	// Test connection
	ctx := context.Background()
	_, err := rdb.Ping(ctx).Result()
	if err != nil {
		return nil, err
	}

	log.Println("Successfully connected to Redis cache")
	return &RedisClient{Client: rdb}, nil
}
