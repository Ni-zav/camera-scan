package dev.nizav.documentscanner.data.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "projects")
public final class ProjectEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;
    public long createdAt;
    public long updatedAt;
}
