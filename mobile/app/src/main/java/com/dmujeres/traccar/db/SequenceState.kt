package com.dmujeres.traccar.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Secuencia durable del outbox. Los huecos son válidos; la reutilización no. */
@Entity(tableName = "sequence_state")
data class SequenceState(
    @PrimaryKey val id: Int = 1,
    val sequence: Long = 0L,
)
