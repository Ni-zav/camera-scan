package dev.nizav.documentscanner.data.db;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "pages",
        foreignKeys = @ForeignKey(
                entity = ProjectEntity.class,
                parentColumns = "id",
                childColumns = "projectId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("projectId"),
                @Index(value = {"projectId", "position"}, unique = true)
        }
)
public final class PageEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long projectId;
    public int position;
    public String filePath;

    public String ocrText;
    public String ocrDataJson;
    public long ocrUpdatedAt;

    public long createdAt;
    public long updatedAt;
}
